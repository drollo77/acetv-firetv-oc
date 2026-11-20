package local.acetv

import java.net.URLDecoder

/** Lo que el servidor del movil necesita de la app. */
interface RemoteController {
    fun channels(): List<Channel>
    fun playing(): Channel?
    fun engineHost(): String
    fun engineMode(): String
    fun listSource(): String
    fun play(hash: String)
    fun zap(delta: Int)
    fun setEngineHost(host: String)
    /** null = volver a la lista incluida en la app. */
    fun applyList(list: List<Channel>?)
    fun httpGet(url: String): String
    fun page(): String
}

/** Rutas bajo /s/<codigo>/ (pagina del movil y API JSON). */
class RemoteApi(private val session: RemoteSession, private val ctl: RemoteController) {
    @Volatile private var pending: List<Channel>? = null

    fun handle(req: HttpRequest): HttpResponse {
        if (!req.path.startsWith("/s/")) return err(404, "no encontrado")
        val rest = req.path.substring(3)
        val slash = rest.indexOf('/')
        val code = if (slash < 0) rest else rest.substring(0, slash)
        val sub = if (slash < 0) "" else rest.substring(slash + 1)
        when (session.check(code)) {
            RemoteSession.Result.BAD -> return err(404, "no encontrado")
            RemoteSession.Result.LOCKED -> return err(403, "sesion bloqueada: genera un QR nuevo en la TV")
            RemoteSession.Result.EXPIRED -> return err(410, "sesion caducada: genera un QR nuevo en la TV")
            RemoteSession.Result.OK -> {}
        }
        return try {
            route(req, code, sub)
        } catch (e: HttpError) {
            err(e.status, e.message ?: "error")
        } catch (e: Exception) {
            err(500, "error interno")
        }
    }

    private fun err(status: Int, msg: String) =
        HttpResponse.json(status, "{\"ok\":false,\"error\":" + JsonOut.str(msg) + "}")

    private fun okFalse(msg: String) = err(200, msg)

    private fun ok(msg: String) =
        HttpResponse.json(200, "{\"ok\":true,\"message\":" + JsonOut.str(msg) + "}")

    private fun route(req: HttpRequest, code: String, sub: String): HttpResponse {
        val get = req.method == "GET"
        val post = req.method == "POST"
        val no = err(405, "metodo no permitido")
        return when (sub) {
            "", "index.html" -> if (get) HttpResponse.html(ctl.page().replace("{{CODE}}", code)) else no
            "api/state" -> if (get) state() else no
            "api/search" -> if (get) search(req) else no
            "api/play" -> if (post) play(req) else no
            "api/zap" -> if (post) zap(req) else no
            "api/engine" -> if (post) engine(req) else no
            "api/list/preview" -> if (post) preview(req) else no
            "api/list/apply" -> if (post) apply(req) else no
            else -> err(404, "no encontrado")
        }
    }

    private fun bodyMap(req: HttpRequest): Map<*, *> {
        val txt = String(req.body, Charsets.UTF_8)
        val v = try {
            MiniJson.parse(txt)
        } catch (e: Exception) {
            throw HttpError(400, "JSON no valido")
        }
        return v as? Map<*, *> ?: throw HttpError(400, "se esperaba un objeto JSON")
    }

    private fun state(): HttpResponse {
        val p = ctl.playing()
        val list = ctl.channels()
        val playing = if (p == null) "null"
        else "{\"title\":" + JsonOut.str(p.title) + ",\"hash\":" + JsonOut.str(p.hash) + "}"
        return HttpResponse.json(
            200,
            "{\"ok\":true,\"playing\":" + playing +
                ",\"host\":" + JsonOut.str(ctl.engineHost()) +
                ",\"mode\":" + JsonOut.str(ctl.engineMode()) +
                ",\"list\":{\"source\":" + JsonOut.str(ctl.listSource()) +
                ",\"count\":" + list.size + ",\"groups\":" + ChannelLogic.groupsOf(list).size + "}" +
                ",\"secondsLeft\":" + session.secondsLeft() + "}"
        )
    }

    private fun search(req: HttpRequest): HttpResponse {
        val q = req.query["q"] ?: ""
        val limit = (req.query["limit"]?.toIntOrNull() ?: 60).coerceIn(1, 200)
        val (total, items) = Search.filter(ctl.channels(), q, limit)
        val arr = items.joinToString(",") {
            "{\"title\":" + JsonOut.str(it.title) + ",\"hash\":" + JsonOut.str(it.hash) +
                ",\"group\":" + JsonOut.str(it.group) + "}"
        }
        return HttpResponse.json(200, "{\"ok\":true,\"total\":$total,\"items\":[$arr]}")
    }

    private fun play(req: HttpRequest): HttpResponse {
        val raw = bodyMap(req)["hash"] as? String ?: ""
        val hash = ListParsers.hashFrom(raw) ?: return okFalse("El ID debe tener 40 caracteres hexadecimales")
        ctl.play(hash)
        val title = ctl.channels().firstOrNull { it.hash == hash }?.title ?: "ID manual"
        return ok("Reproduciendo: $title")
    }

    private fun zap(req: HttpRequest): HttpResponse {
        val d = (bodyMap(req)["delta"] as? Number)?.toInt() ?: 0
        if (d == 0) return okFalse("Indica delta 1 o -1")
        ctl.zap(if (d > 0) 1 else -1)
        return ok("Hecho")
    }

    private fun engine(req: HttpRequest): HttpResponse {
        val host = ((bodyMap(req)["host"] as? String) ?: "").trim()
        if (!EngineCheck.validHost(host)) return okFalse("Direccion no valida")
        val (found, msg) = EngineCheck.check(host) { ctl.httpGet(it) }
        if (!found) return okFalse(msg + ". No se ha cambiado nada.")
        ctl.setEngineHost(host)
        return ok(msg)
    }

    private fun preview(req: HttpRequest): HttpResponse {
        val name = try {
            URLDecoder.decode(req.headers["x-filename"] ?: "", "UTF-8")
        } catch (e: Exception) {
            ""
        }
        val text = String(req.body, Charsets.UTF_8).removePrefix("\uFEFF")
        val lower = name.lowercase()
        val isM3u = lower.endsWith(".m3u") || lower.endsWith(".m3u8") || text.trimStart().startsWith("#EXTM3U")
        val parsed = try {
            if (isM3u) ListParsers.parseM3u(text) else ListParsers.parseJson(text)
        } catch (e: Exception) {
            return okFalse("No se pudo leer la lista: " + (e.message ?: "formato no valido"))
        }
        val unique = ChannelLogic.dedupe(parsed)
        if (unique.isEmpty()) return okFalse("La lista no contiene canales con ID valido")
        pending = unique
        val counts = LinkedHashMap<String, Int>()
        for (c in unique) counts[c.group] = (counts[c.group] ?: 0) + 1
        val groups = counts.entries.take(40).joinToString(",") {
            "{\"name\":" + JsonOut.str(it.key) + ",\"count\":" + it.value + "}"
        }
        val kind = if (isM3u) "m3u" else "json"
        val rows = ChannelMerge.merge(unique).size
        return HttpResponse.json(
            200,
            "{\"ok\":true,\"kind\":\"$kind\",\"total\":${parsed.size},\"unique\":${unique.size}," +
                "\"rows\":$rows,\"duplicates\":${parsed.size - unique.size},\"groupCount\":${counts.size}," +
                "\"groups\":[$groups]}"
        )
    }

    private fun apply(req: HttpRequest): HttpResponse {
        return when (bodyMap(req)["action"] as? String) {
            "replace" -> {
                val p = pending ?: return okFalse("No hay ninguna lista pendiente: sube un fichero primero")
                pending = null
                ctl.applyList(p)
                ok("Lista sustituida: " + p.size + " canales")
            }
            "reset" -> {
                pending = null
                ctl.applyList(null)
                ok("Vuelve la lista incluida en la app")
            }
            else -> okFalse("Accion desconocida")
        }
    }
}
