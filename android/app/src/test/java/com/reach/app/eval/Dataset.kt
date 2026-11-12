package com.reach.app.eval

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.reach.app.grounding.Appearance
import com.reach.app.grounding.Box
import com.reach.app.hand.HandResult
import com.reach.app.hand.Lm
import java.io.File

/** dataset/categories.json: the supported categories and which current-model labels count as each. */
data class Category(val id: Int, val name: String, val modelLabels: List<String>, val support: String)

class CategorySpec(val categories: List<Category>, val handCategories: List<Category>) {
    private val byLabel = categories.flatMap { c -> c.modelLabels.map { it to c.name } }.toMap()
    val names get() = categories.map { it.name }
    /** What detection metrics are reported for: the supported targets and those REACH wants but cannot detect yet. */
    val scored get() = categories.filter { it.support != "excluded" }.map { it.name }
    /** The category a model label counts as, or null for labels outside the spec (e.g. "bed", "tv"). */
    fun categoryOf(modelLabel: String): String? = byLabel[modelLabel]
    fun nameOf(id: Int): String? = (categories + handCategories).firstOrNull { it.id == id }?.name

    companion object {
        fun load(file: File): CategorySpec {
            val j = JsonParser.parseString(file.readText()).asJsonObject
            fun cats(key: String) = j.getAsJsonArray(key)?.map { e -> e.asJsonObject.let { o ->
                Category(o["id"].asInt, o["name"].asString, o.getAsJsonArray("model_labels")?.map { it.asString }.orEmpty(),
                    o["model_support"]?.asString ?: "")
            } }.orEmpty()
            return CategorySpec(cats("categories"), cats("hand_categories"))
        }
    }
}

/** [instance]: the physical object across sessions ("laptop-black-dell"), for registered-object matching. */
data class GtObject(val category: String, val box: Box, val track: String?, val instance: String? = null)

/** One labelled image. [labeled] = categories whose annotations are exhaustive in this image (others are ignored). */
data class Frame(
    val file: String, val session: String, val sequence: String, val ms: Long, val lighting: String,
    val synthetic: Boolean, val labeled: Set<String>, val objects: List<GtObject>, val hands: List<GtObject>,
    val target: String?, val reachMs: Long?,
    val references: Set<String> = emptySet() // instances registered from this image (images.csv "reference")
)

/** COCO annotations.json (boxes in pixels) + images.csv (per-image attributes); see dataset/README.md. */
fun loadDataset(dir: File, spec: CategorySpec): List<Frame> {
    val coco = JsonParser.parseString(File(dir, "annotations.json").readText()).asJsonObject
    val images = coco.getAsJsonArray("images").map { it.asJsonObject }.associateBy { it["id"].asInt }
    val anns = coco.getAsJsonArray("annotations").map { it.asJsonObject }.groupBy { it["image_id"].asInt }
    val rows = File(dir, "images.csv").readLines().filter { it.isNotBlank() }
    val header = rows.first().split(',').map { it.trim() }
    val attrs = rows.drop(1).map { r -> header.zip(r.split(',').map { it.trim() }).toMap() }.associateBy { it.getValue("file_name") }
    return images.map { (id, img) ->
        val file = img["file_name"].asString
        val a = attrs[file] ?: error("$file is in annotations.json but not in images.csv")
        val w = img["width"].asFloat; val h = img["height"].asFloat
        val objs = anns[id].orEmpty().map { o ->
            val b = o.getAsJsonArray("bbox").map { it.asFloat }
            fun attr(k: String) = (o[k] ?: o.getAsJsonObject("attributes")?.get(k))?.asString?.ifEmpty { null }
            GtObject(spec.nameOf(o["category_id"].asInt) ?: error("unknown category_id in $file"),
                Box(b[0] / w, b[1] / h, (b[0] + b[2]) / w, (b[1] + b[3]) / h), attr("track_id"), attr("instance"))
        }
        val handNames = spec.handCategories.map { it.name }.toSet()
        Frame(file, a.getValue("session"), a["sequence"].orEmpty().ifEmpty { a.getValue("session") },
            a["frame_ms"]?.toLongOrNull() ?: 0L, a["lighting"].orEmpty(), a["synthetic"] == "1",
            a["labeled"].let { if (it.isNullOrEmpty() || it == "all") spec.names.toSet() else it.split('|').toSet() },
            objs.filter { it.category !in handNames }, objs.filter { it.category in handNames },
            a["target"]?.ifEmpty { null }, a["reach_ms"]?.toLongOrNull(),
            a["reference"].orEmpty().split('|').filter { it.isNotEmpty() }.toSet())
    }.sortedWith(compareBy({ it.sequence }, { it.ms }, { it.file }))
}

/** [look]: brightness and contrast of the box as the app measures them (no colours), when the replay wrote them. */
data class Pred(val label: String, val score: Float, val box: Box, val look: Appearance? = null, val emb: FloatArray? = null)
data class PredFrame(val dets: List<Pred>, val hands: List<HandResult>, val detMs: Double, val handMs: Double, val luma: Double)
class Predictions(val frames: Map<String, PredFrame>, val summary: JsonObject?)

/** The predictions_<model>.jsonl that DebugFrames.replay writes on the phone. */
fun loadPredictions(file: File): Predictions {
    var summary: JsonObject? = null
    val frames = HashMap<String, PredFrame>()
    file.readLines().filter { it.isNotBlank() }.forEach { line ->
        val o = JsonParser.parseString(line).asJsonObject
        if (o.has("summary")) { summary = o.getAsJsonObject("summary"); return@forEach }
        frames[o["image"].asString] = PredFrame(
            o.getAsJsonArray("dets").map { d -> d.asJsonObject.let { x ->
                val b = x.getAsJsonArray("box").map { it.asFloat }
                Pred(x["label"].asString, x["score"].asFloat, Box(b[0], b[1], b[2], b[3]),
                    x["luma"]?.let { Appearance(FloatArray(Appearance.COLORS.size), it.asFloat, x["contrast"].asFloat) },
                    x.getAsJsonArray("emb")?.map { it.asFloat }?.toFloatArray())
            } },
            o.getAsJsonArray("hands").map { h -> h.asJsonObject.let { x ->
                HandResult.fromLandmarks(x.getAsJsonArray("lm").map { p -> p.asJsonArray.let { Lm(it[0].asFloat, it[1].asFloat, it[2].asFloat) } },
                    x["score"].asFloat)
            } },
            o["det_ms"].asDouble, o["hand_ms"].asDouble, o["luma"]?.asDouble ?: 255.0)
    }
    return Predictions(frames, summary)
}
