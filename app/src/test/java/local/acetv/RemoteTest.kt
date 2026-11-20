package local.acetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val H1 = "0123456789abcdef0123456789abcdef01234567"
private const val H2 = "fedcba9876543210fedcba9876543210fedcba98"
private const val H3 = "aaaaaaaaaabbbbbbbbbbccccccccccdddddddddd"

class RemoteSessionTest {
    private var now = 1000L
    private fun session(max: Int = 30) =
        RemoteSession(ttlMs = 300000L, maxBad = max, clock = { now }, newCode = { "abcd1234" })

    @Test
    fun codigoCorrectoYErroneo() {
        val s = session()
        assertEquals(RemoteSession.Result.OK, s.check("abcd1234"))
        assertEquals(RemoteSession.Result.BAD, s.check("otro"))
    }

    @Test
    fun seBloqueaALos30IntentosErroneos() {
        val s = session()
        for (i in 1..29) assertEquals(RemoteSession.Result.BAD, s.check("mal$i"))
        assertEquals(RemoteSession.Result.LOCKED, s.check("mal30"))
        assertEquals(RemoteSession.Result.LOCKED, s.check("abcd1234"))
        s.renew()
        assertEquals(RemoteSession.Result.OK, s.check("abcd1234"))
    }

    @Test
    fun caducaTras5MinutosSinActividad() {
        val s = session()
        now += 4 * 60 * 1000L
        assertEquals(RemoteSession.Result.OK, s.check("abcd1234"))
        now += 4 * 60 * 1000L
        assertEquals(RemoteSession.Result.OK, s.check("abcd1234"))
        now += 5 * 60 * 1000L + 1
        assertEquals(RemoteSession.Result.EXPIRED, s.check("abcd1234"))
        s.renew()
        assertEquals(RemoteSession.Result.OK, s.check("abcd1234"))
    }

    @Test
    fun segundosRestantes() {
        val s = session()
        assertEquals(300L, s.secondsLeft())
        now += 100 * 1000L
        assertEquals(200L, s.secondsLeft())
        now += 1000 * 1000L
        assertEquals(0L, s.secondsLeft())
    }

    @Test
    fun codigoAleatorioDe8Hex() {
        val a = RemoteSession.randomCode()
        val b = RemoteSession.randomCode()
        assertTrue(Regex("^[0-9a-f]{8}$").matches(a))
        assertTrue(a != b)
    }
}

class SearchAndEngineTest {
    private val list = listOf(
        Channel("La 1", H1, "TDT"),
        Channel("Fútbol Movistar", H2, "DEPORTES"),
        Channel("DAZN 1", H3, "DEPORTES")
    )

    @Test
    fun normalizaTildesYMayusculas() {
        assertEquals("futbol nandu", Search.normalize("Fútbol ÑANDÚ").replace("ñ", "n"))
        assertEquals("camion", Search.normalize("CAMIÓN"))
    }

    @Test
    fun todasLasPalabrasYSinTildes() {
        assertEquals(1, Search.filter(list, "FUTBOL", 10).first)
        assertEquals(1, Search.filter(list, "movistar futbol", 10).first)
        assertEquals(0, Search.filter(list, "movistar dazn", 10).first)
        assertEquals(2, Search.filter(list, "deportes", 10).first)
    }

    @Test
    fun consultaVaciaYLimite() {
        val (total, items) = Search.filter(list, "  ", 2)
        assertEquals(3, total)
        assertEquals(2, items.size)
    }

    @Test
    fun hostsValidos() {
        assertTrue(EngineCheck.validHost("192.168.1.34"))
        assertTrue(EngineCheck.validHost("mi-motor.local"))
        assertFalse(EngineCheck.validHost("300.1.1.1"))
        assertFalse(EngineCheck.validHost("a b"))
        assertFalse(EngineCheck.validHost("http://x"))
        assertFalse(EngineCheck.validHost(""))
        assertFalse(EngineCheck.validHost("192.168.1.34:6878"))
    }

    @Test
    fun deteccionDelMotor() {
        val v = """x({"result": {"code": 3014900, "platform": "linux", "version": "3.1.49"}, "error": null});"""
        val a = EngineCheck.check("10.0.0.5") { if (it.contains("get_version")) v else throw java.io.IOException("no") }
        assertTrue(a.first)
        assertTrue(a.second.contains("3.1.49"))

        val b = EngineCheck.check("10.0.0.5") {
            if (it.contains("get_version")) throw java.io.IOException("no") else """{"response": null, "error": "id"}"""
        }
        assertTrue(b.first)

        val c = EngineCheck.check("10.0.0.5") { throw java.io.IOException("sin ruta") }
        assertFalse(c.first)
        assertTrue(c.second.contains("sin ruta"))

        val d = EngineCheck.check("10.0.0.5") { "<html>nada</html>" }
        assertFalse(d.first)
    }
}

class FakeController : RemoteController {
    var list: List<Channel> = listOf(
        Channel("La 1", H1, "TDT"),
        Channel("Fútbol Movistar", H2, "DEPORTES"),
        Channel("DAZN 1", H3, "DEPORTES")
    )
    var current: Channel? = null
    var host = "192.168.1.34"
    val played = ArrayList<String>()
    val zaps = ArrayList<Int>()
    var applied: List<Channel>? = null
    var applyCalls = 0
    val engine = HashMap<String, String>()

    override fun channels() = list
    override fun playing() = current
    override fun engineHost() = host
    override fun engineMode() = "ts"
    override fun listSource() = "incluida"
    override fun play(hash: String) { played.add(hash) }
    override fun zap(delta: Int) { zaps.add(delta) }
    override fun setEngineHost(host: String) { this.host = host }
    override fun applyList(list: List<Channel>?) { applied = list; applyCalls++ }
    override fun httpGet(url: String): String =
        engine.entries.firstOrNull { url.contains(it.key) }?.value ?: throw java.io.IOException("sin conexion")
    override fun page() = "<html>codigo={{CODE}}</html>"
}

class RemoteApiTest {
    private var now = 1000L
    private val ctl = FakeController()
    private val session = RemoteSession(ttlMs = 300000L, maxBad = 30, clock = { now }, newCode = { "abcd1234" })
    private val api = RemoteApi(session, ctl)
    private val base = "/s/abcd1234/"

    private fun get(path: String, query: Map<String, String> = emptyMap()) =
        api.handle(HttpRequest("GET", path, query, emptyMap(), ByteArray(0)))

    private fun post(path: String, body: String, headers: Map<String, String> = emptyMap()) =
        api.handle(HttpRequest("POST", path, emptyMap(), headers, body.toByteArray(Charsets.UTF_8)))

    private fun json(r: HttpResponse): Map<*, *> = MiniJson.parse(String(r.body, Charsets.UTF_8)) as Map<*, *>

    @Test
    fun paginaConElCodigo() {
        val a = get("/s/abcd1234/")
        assertEquals(200, a.status)
        assertTrue(String(a.body, Charsets.UTF_8).contains("codigo=abcd1234"))
        assertEquals(200, get("/s/abcd1234").status)
        assertEquals(200, get("/s/abcd1234/index.html").status)
    }

    @Test
    fun codigoMaloYRutasAjenas() {
        assertEquals(404, get("/s/zzzz/").status)
        assertEquals(404, get("/otra/cosa").status)
        assertEquals(404, get(base + "nada").status)
    }

    @Test
    fun estado() {
        val r = json(get(base + "api/state"))
        assertEquals(true, r["ok"])
        assertEquals("192.168.1.34", r["host"])
        assertNull(r["playing"])
        val list = r["list"] as Map<*, *>
        assertEquals(3L, list["count"])
        assertEquals(2L, list["groups"])
        ctl.current = ctl.list[1]
        val p = json(get(base + "api/state"))["playing"] as Map<*, *>
        assertEquals("Fútbol Movistar", p["title"])
    }

    @Test
    fun busqueda() {
        val a = json(get(base + "api/search", mapOf("q" to "futbol")))
        assertEquals(1L, a["total"])
        assertEquals("Fútbol Movistar", ((a["items"] as List<*>)[0] as Map<*, *>)["title"])
        val b = json(get(base + "api/search", mapOf("q" to "", "limit" to "1")))
        assertEquals(3L, b["total"])
        assertEquals(1, (b["items"] as List<*>).size)
    }

    @Test
    fun reproducirYZapear() {
        assertEquals(true, json(post(base + "api/play", """{"hash":"$H2"}"""))["ok"])
        assertEquals(listOf(H2), ctl.played)
        val mal = json(post(base + "api/play", """{"hash":"123"}"""))
        assertEquals(false, mal["ok"])
        assertEquals(1, ctl.played.size)
        post(base + "api/zap", """{"delta":1}""")
        post(base + "api/zap", """{"delta":-5}""")
        assertEquals(listOf(1, -1), ctl.zaps)
        assertEquals(false, json(post(base + "api/zap", """{"delta":0}"""))["ok"])
    }

    @Test
    fun cambioDeMotor() {
        ctl.engine["get_version"] = """x({"result": {"version": "3.2.11"}, "error": null});"""
        val ok = json(post(base + "api/engine", """{"host":"10.0.0.7"}"""))
        assertEquals(true, ok["ok"])
        assertTrue((ok["message"] as String).contains("3.2.11"))
        assertEquals("10.0.0.7", ctl.host)

        val malo = json(post(base + "api/engine", """{"host":"a b"}"""))
        assertEquals(false, malo["ok"])
        assertEquals("10.0.0.7", ctl.host)

        ctl.engine.clear()
        val caido = json(post(base + "api/engine", """{"host":"10.0.0.8"}"""))
        assertEquals(false, caido["ok"])
        assertEquals("10.0.0.7", ctl.host)
    }

    @Test
    fun subirListaVistaPreviaYSustituir() {
        val txt = """{"hashes":[{"hash":"$H1","title":"A","group":"G"},{"hash":"$H2","title":"B","group":"G"},{"hash":"$H1","title":"A2","group":"H"}]}"""
        val p = json(post(base + "api/list/preview", txt, mapOf("x-filename" to "mi%20lista.json")))
        assertEquals(true, p["ok"])
        assertEquals("json", p["kind"])
        assertEquals(3L, p["total"])
        assertEquals(2L, p["unique"])
        assertEquals(1L, p["duplicates"])
        assertEquals(2L, p["rows"])

        assertEquals(0, ctl.applyCalls)
        val a = json(post(base + "api/list/apply", """{"action":"replace"}"""))
        assertEquals(true, a["ok"])
        assertEquals(2, ctl.applied?.size)
        assertEquals(1, ctl.applyCalls)

        assertEquals(false, json(post(base + "api/list/apply", """{"action":"replace"}"""))["ok"])
        assertEquals(1, ctl.applyCalls)
    }

    @Test
    fun subirM3uYVolverALaIncluida() {
        val m3u = "#EXTM3U\n#EXTINF:-1 group-title=\"X\",Canal --> ORIGEN\nacestream://$H3\n"
        val p = json(post(base + "api/list/preview", m3u, mapOf("x-filename" to "lista.m3u")))
        assertEquals(true, p["ok"])
        assertEquals("m3u", p["kind"])
        assertEquals(1L, p["unique"])
        post(base + "api/list/apply", """{"action":"replace"}""")
        assertEquals(H3, ctl.applied!![0].hash)

        assertEquals(true, json(post(base + "api/list/apply", """{"action":"reset"}"""))["ok"])
        assertNull(ctl.applied)
        assertEquals(2, ctl.applyCalls)
    }

    @Test
    fun listasInvalidas() {
        assertEquals(false, json(post(base + "api/list/preview", "esto no es json", mapOf("x-filename" to "a.json")))["ok"])
        assertEquals(false, json(post(base + "api/list/preview", """{"hashes":[{"hash":"zz"}]}"""))["ok"])
        assertEquals(false, json(post(base + "api/list/apply", """{"action":"otra"}"""))["ok"])
    }

    @Test
    fun erroresDeProtocolo() {
        assertEquals(405, get(base + "api/play").status)
        assertEquals(405, post(base + "api/state", "{}").status)
        assertEquals(400, post(base + "api/play", "xx").status)
        assertEquals(400, post(base + "api/play", "[1]").status)
    }

    @Test
    fun caducaYSeBloquea() {
        now += 300001L
        assertEquals(410, get(base + "api/state").status)
        session.renew()
        assertEquals(200, get(base + "api/state").status)
        for (i in 1..29) assertEquals(404, get("/s/mal$i/").status)
        assertEquals(403, get("/s/mal30/").status)
        assertEquals(403, get(base + "api/state").status)
    }
}

class ListJsonTest {
    @Test
    fun idaYVueltaConCaracteresEspeciales() {
        val l = listOf(
            Channel("Fútbol \"HD\"\n2", H1, "DEPORTES", "http://x/l.png", "id.es", "ORIGEN uno"),
            Channel("Ñandú \\ canal", H2, "Sin grupo")
        )
        val back = ListParsers.parseJson(ListParsers.toJson(l))
        assertEquals(l, back)
        assertTrue(ListParsers.toJson(emptyList()).contains("\"count\":0"))
    }
}
