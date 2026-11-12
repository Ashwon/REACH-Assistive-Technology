package com.reach.app.claude

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.reach.app.BuildConfig
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.SupportedClasses
import com.reach.app.grounding.TargetPick
import com.reach.app.grounding.clarifyQuestion
import com.reach.app.grounding.pickTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * V5 — Talks to the backend proxy (backend/server.py), never to Anthropic
 * directly: no API key ships in the APK.
 *
 * If the backend/network is down, interpretLocal() gives a keyword-match
 * fallback so target selection still works offline (fallback architecture).
 */
data class Interpretation(
    val intent: String,            // REACH_OBJECT | DESCRIBE | UNKNOWN
    val target: String,            // "coke" etc.
    val candidateId: Int,          // -1 if none
    val confidence: Double,
    val needsClarification: Boolean,
    val question: String,          // clarification question or spoken ack
    val fromClaude: Boolean
)

class ClaudeClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun interpret(utterance: String, candidates: List<Candidate>): Interpretation =
        withContext(Dispatchers.IO) {
            // A category REACH cannot find is answered locally, the same way every time; Claude never re-maps it.
            if (SupportedClasses.resolve(utterance)?.supported == false) return@withContext interpretLocal(utterance, candidates)
            try {
                val body = JSONObject().apply {
                    put("utterance", utterance)
                    put("candidates", JSONArray().apply {
                        candidates.forEach { c ->
                            put(JSONObject().apply {
                                put("id", c.id)
                                put("label", c.label)
                                put("beverage_guess", c.beverage)
                                put("cx", c.box.centerX())
                                put("cy", c.box.centerY())
                                put("score", c.score)
                                c.crop?.let { put("thumb_b64", toB64(it)) }
                            })
                        }
                    })
                }.toString()

                val req = Request.Builder()
                    .url("${BuildConfig.BACKEND_URL}/interpret")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("backend ${resp.code}")
                    val j = JSONObject(resp.body!!.string())
                    Interpretation(
                        intent = j.optString("intent", "UNKNOWN"),
                        target = j.optString("target", ""),
                        candidateId = j.optInt("candidate_id", -1),
                        confidence = j.optDouble("confidence", 0.0),
                        needsClarification = j.optBoolean("needs_clarification", false),
                        question = j.optString("question", ""),
                        fromClaude = true
                    )
                }
            } catch (e: Exception) {
                Log.w("REACH", "backend unavailable (${e.javaClass.simpleName}: ${e.message}); offline fallback")
                interpretLocal(utterance, candidates)
            }
        }

    /**
     * Offline fallback: resolves the spoken object to supported COCO classes (SupportedClasses).
     * Confidence is the detector's own score. Several matches are never picked silently: it asks.
     */
    fun interpretLocal(utterance: String, candidates: List<Candidate>): Interpretation {
        val spec = SupportedClasses.resolve(utterance)
            ?: return Interpretation("UNKNOWN", "", -1, 0.0, true,
                "I didn't catch which object you want. ${SupportedClasses.SUPPORTED_LINE}", false)
        if (!spec.supported) return Interpretation("REACH_OBJECT", spec.name, -1, 0.0, true, SupportedClasses.cannotFind(spec), false)
        return when (val pick = pickTarget(spec, candidates)) {
            is TargetPick.One ->
                Interpretation("REACH_OBJECT", spec.name, pick.c.id, pick.c.score.toDouble(), false, "", false)
            is TargetPick.Ambiguous ->
                Interpretation("REACH_OBJECT", spec.name, -1, 0.0, true,
                    "I can help. " + clarifyQuestion(spec, pick.options.size), false)
            TargetPick.None ->
                Interpretation("REACH_OBJECT", spec.name, -1, 0.0, true,
                    "I can't see a ${spec.name} right now. Try panning slowly.", false)
        }
    }

    private fun toB64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
