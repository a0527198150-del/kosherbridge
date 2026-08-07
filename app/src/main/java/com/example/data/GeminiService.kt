package com.example.data

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- Gemini API Models ---

@JsonClass(generateAdapter = true)
data class GeminiPart(
    val text: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    val responseMimeType: String? = "application/json",
    val temperature: Float? = 0.1f
)

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
    val systemInstruction: GeminiContent? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    val content: GeminiContent
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null
)

// --- Parsed Result Model ---

@JsonClass(generateAdapter = true)
data class ParsedTransaction(
    val title: String,
    val amount: Double,
    val isExpense: Boolean,
    val categoryName: String,
    val paymentType: String // "CASH" or "CREDIT"
)

// --- Retrofit Service ---

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }
}

// --- Gemini Parser Implementation ---

object GeminiExpenseParser {
    
    suspend fun parseExpense(
        prompt: String,
        categories: List<String>,
        apiKey: String
    ): ParsedTransaction? {
        val categoriesStr = categories.joinToString(separator = ", ") { "\"$it\"" }
        
        val systemInstructionText = """
            אתה עוזר פיננסי חכם לאפליקציית תקציב עברי. תפקידך לנתח משפט חופשי של משתמש המתאר הוצאה או הכנסה, ולחלץ ממנו את הפרטים בפורמט JSON בלבד.
            הערכים שאתה חייב להחזיר הם:
            - title (String): שם קצר ומדויק של הפריט או מקום הקנייה (לדוגמה: "חומוס אליהו", "סופר ברכל", "משכורת", "קניית חולצה", "תשלום סלולר").
            - amount (Double): סכום כספי כמספר עשרוני חיובי (לדוגמה: 45.0, 120.5). אם לא צויין סכום, החזר 0.0.
            - isExpense (Boolean): true עבור הוצאה (קנייה, תשלום כלשהו, הוצאה כספית), false עבור הכנסה (משכורת, מלגה, מתנה שקיבל, החזר כספי).
            - categoryName (String): עליך לבחור את הקטגוריה המתאימה ביותר מתוך הרשימה הבאה בלבד: [$categoriesStr].
              אם הטקסט לא מתאים במובהק לאף קטגוריה, אך יש קטגוריה חדשה שהמשתמש ציין במפורש - החזר את החדשה. אחרת, אם אין התאמה מושלמת, החזר "אחר".
            - paymentType (String): "CASH" עבור מזומן (למשל "מזומן", "ביד", "שטר"), "CREDIT" עבור אשראי (למשל "אשראי", "כרטיס", "צ'ק", "העברה" או כברירת מחדל אם לא צויין כלל).

            החזר אך ורק אובייקט JSON תקין אחד שתואם בדיוק למבנה הזה, ללא שום טקסט נוסף לפניו או אחריו.
            לדוגמה:
            {"title": "חומוס אליהו", "amount": 45.0, "isExpense": true, "categoryName": "אוכל מוכן", "paymentType": "CREDIT"}
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(GeminiPart(text = prompt))
                )
            ),
            generationConfig = GeminiGenerationConfig(
                responseMimeType = "application/json",
                temperature = 0.1f
            ),
            systemInstruction = GeminiContent(
                parts = listOf(GeminiPart(text = systemInstructionText))
            )
        )

        return try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText != null) {
                // Strip out markdown code blocks if the model wrapped the JSON
                var cleanedJson = jsonText.trim()
                if (cleanedJson.startsWith("```")) {
                    cleanedJson = cleanedJson.removePrefix("```")
                    if (cleanedJson.startsWith("json", ignoreCase = true)) {
                        cleanedJson = cleanedJson.removePrefix("json")
                    }
                    cleanedJson = cleanedJson.trim()
                }
                if (cleanedJson.endsWith("```")) {
                    cleanedJson = cleanedJson.removeSuffix("```")
                    cleanedJson = cleanedJson.trim()
                }

                // Parse cleaned JSON using Moshi
                val adapter = RetrofitClient.moshi.adapter(ParsedTransaction::class.java)
                adapter.fromJson(cleanedJson)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
