package com.example.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/**
 * Google Sign-In via the modern Credential Manager API, then links the Google
 * ID token to Firebase Auth so the app gets a stable user identity (uid) that
 * is used to scope the cloud backup/restore data in Firestore.
 */
class GoogleAuthManager(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)
    private val firebaseAuth = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser?
        get() = firebaseAuth.currentUser

    /**
     * Launches the Google account picker. Must be called from the main thread.
     * Returns the signed-in FirebaseUser on success.
     */
    suspend fun signIn(activity: Activity): Result<FirebaseUser> {
        return try {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(
                    GetGoogleIdOption.Builder()
                        .setServerClientId(WEB_CLIENT_ID)
                        .setFilterByAuthorizedAccounts(false)
                        .build()
                )
                .build()

            val result = credentialManager.getCredential(activity, request)
            val credential = result.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                val user = firebaseAuth.signInWithCredential(firebaseCredential).await().user
                if (user != null) {
                    Result.success(user)
                } else {
                    Result.failure(IllegalStateException("ההתחברות נכשלה"))
                }
            } else {
                Result.failure(IllegalStateException("סוג התחברות לא נתמך"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut() {
        firebaseAuth.signOut()
    }

    companion object {
        // Web client ID (client_type 3) from google-services.json - used by Credential Manager.
        const val WEB_CLIENT_ID =
            "1008115688519-n073lq1vmsrkmgj798j6ig306n6eo21r.apps.googleusercontent.com"
    }
}
