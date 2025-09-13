package com.cpen321.usermanagement.data.repository

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.cpen321.usermanagement.BuildConfig
import com.cpen321.usermanagement.data.local.preferences.TokenManager
import com.cpen321.usermanagement.data.remote.api.AuthInterface
import com.cpen321.usermanagement.data.remote.api.RetrofitClient
import com.cpen321.usermanagement.data.remote.api.UserInterface
import com.cpen321.usermanagement.data.remote.dto.AuthData
import com.cpen321.usermanagement.data.remote.dto.GoogleLoginRequest
import com.cpen321.usermanagement.data.remote.dto.User
import com.cpen321.usermanagement.utils.JsonUtils
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authInterface: AuthInterface,
    private val userInterface: UserInterface,
    private val tokenManager: TokenManager
) : AuthRepository {

    companion object {
        private const val TAG = "AuthRepositoryImpl"
    }

    private val credentialManager = CredentialManager.create(context)
    private val signInWithGoogleOption: GetSignInWithGoogleOption =
        GetSignInWithGoogleOption.Builder(
            serverClientId = BuildConfig.GOOGLE_CLIENT_ID
        ).build()

    init {
        // Safe to log just the tail so you can confirm which client is bundled
        Log.d(TAG, "GOOGLE_CLIENT_ID tail: ${BuildConfig.GOOGLE_CLIENT_ID.takeLast(16)}")
    }

    override suspend fun signInWithGoogle(context: Context): Result<GoogleIdTokenCredential> {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
            .build()

        return try {
            Log.d(TAG, "Fetching Google credential via CredentialManager…")
            val response = credentialManager.getCredential(context, request)
            handleSignInWithGoogleOption(response)
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Failed to get credential from CredentialManager", e)
            Result.failure(e)
        }
    }

    private fun handleSignInWithGoogleOption(
        result: GetCredentialResponse
    ): Result<GoogleIdTokenCredential> {
        val credential = result.credential
        return when (credential) {
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdTokenCredential =
                            GoogleIdTokenCredential.createFrom(credential.data)
                        // === NEW: log safe claims from the ID token ===
                        logSafeIdTokenClaims(googleIdTokenCredential.idToken)
                        Result.success(googleIdTokenCredential)
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e(TAG, "Failed to parse Google ID token credential", e)
                        Result.failure(e)
                    }
                } else {
                    Log.e(TAG, "Unexpected type of credential: ${credential.type}")
                    Result.failure(Exception("Unexpected type of credential"))
                }
            }
            else -> {
                Log.e(TAG, "Unexpected type of credential: ${credential::class.simpleName}")
                Result.failure(Exception("Unexpected type of credential"))
            }
        }
    }

    // === NEW: Helper to log safe claims (aud/iss/exp) from the ID token payload ===
    private fun logSafeIdTokenClaims(idToken: String) {
        try {
            val parts = idToken.split(".")
            if (parts.size < 2) {
                Log.w(TAG, "ID token has unexpected format")
                return
            }
            val payloadJson = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                StandardCharsets.UTF_8
            )
            val obj = JSONObject(payloadJson)
            val aud = obj.optString("aud", "<missing>")
            val iss = obj.optString("iss", "<missing>")
            val expSec = obj.optLong("exp", 0L)
            val email = obj.optString("email", "<hidden>")

            val expHuman = if (expSec > 0) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.format(Date(expSec * 1000))
            } else "<unknown>"

            Log.d(TAG, "ID token claims -> aud: $aud")
            Log.d(TAG, "ID token claims -> iss: $iss")
            Log.d(TAG, "ID token claims -> exp: $expHuman")
            Log.d(TAG, "ID token claims -> email: $email")
            Log.d(TAG, "BuildConfig.GOOGLE_CLIENT_ID: ${BuildConfig.GOOGLE_CLIENT_ID}")
            Log.d(TAG, "aud == BuildConfig.GOOGLE_CLIENT_ID ? ${aud == BuildConfig.GOOGLE_CLIENT_ID}")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to decode/log ID token payload", t)
        }
    }

    override suspend fun googleSignIn(tokenId: String): Result<AuthData> {
        val googleLoginReq = GoogleLoginRequest(tokenId)
        return try {
            Log.d(TAG, "Calling backend /googleSignIn …")
            val response = authInterface.googleSignIn(googleLoginReq)
            if (response.isSuccessful && response.body()?.data != null) {
                val authData = response.body()!!.data!!
                tokenManager.saveToken(authData.token)
                RetrofitClient.setAuthToken(authData.token)
                Log.d(TAG, "Backend sign-in OK; token saved.")
                Result.success(authData)
            } else {
                val errorBodyString = response.errorBody()?.string()
                val errorMessage = JsonUtils.parseErrorMessage(
                    errorBodyString,
                    response.body()?.message ?: "Failed to sign in with Google."
                )
                Log.e(TAG, "Google sign in failed: $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Network timeout during Google sign in", e)
            Result.failure(e)
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Network connection failed during Google sign in", e)
            Result.failure(e)
        } catch (e: java.io.IOException) {
            Log.e(TAG, "IO error during Google sign in", e)
            Result.failure(e)
        } catch (e: retrofit2.HttpException) {
            Log.e(TAG, "HTTP error during Google sign in: ${e.code()}", e)
            Result.failure(e)
        }
    }

    override suspend fun googleSignUp(tokenId: String): Result<AuthData> {
        val googleLoginReq = GoogleLoginRequest(tokenId)
        return try {
            Log.d(TAG, "Calling backend /googleSignUp …")
            val response = authInterface.googleSignUp(googleLoginReq)
            if (response.isSuccessful && response.body()?.data != null) {
                val authData = response.body()!!.data!!
                tokenManager.saveToken(authData.token)
                RetrofitClient.setAuthToken(authData.token)
                Log.d(TAG, "Backend sign-up OK; token saved.")
                Result.success(authData)
            } else {
                val errorBodyString = response.errorBody()?.string()
                val errorMessage = JsonUtils.parseErrorMessage(
                    errorBodyString,
                    response.body()?.message ?: "Failed to sign up with Google."
                )
                Log.e(TAG, "Google sign up failed: $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Network timeout during Google sign up", e)
            Result.failure(e)
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Network connection failed during Google sign up", e)
            Result.failure(e)
        } catch (e: java.io.IOException) {
            Log.e(TAG, "IO error during Google sign up", e)
            Result.failure(e)
        } catch (e: retrofit2.HttpException) {
            Log.e(TAG, "HTTP error during Google sign up: ${e.code()}", e)
            Result.failure(e)
        }
    }

    override suspend fun clearToken(): Result<Unit> {
        tokenManager.clearToken()
        RetrofitClient.setAuthToken(null)
        return Result.success(Unit)
    }

    override suspend fun doesTokenExist(): Boolean {
        return tokenManager.getToken().first() != null
    }

    override suspend fun getStoredToken(): String? {
        return tokenManager.getTokenSync()
    }

    override suspend fun getCurrentUser(): User? {
        return try {
            val response = userInterface.getProfile("") // Auth header via interceptor
            if (response.isSuccessful && response.body()?.data != null) {
                response.body()!!.data!!.user
            } else {
                Log.e(TAG, "Failed to get current user: ${response.body()?.message ?: "Unknown error"}")
                null
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Network timeout while getting current user", e)
            null
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Network connection failed while getting current user", e)
            null
        } catch (e: java.io.IOException) {
            Log.e(TAG, "IO error while getting current user", e)
            null
        } catch (e: retrofit2.HttpException) {
            Log.e(TAG, "HTTP error while getting current user: ${e.code()}", e)
            null
        }
    }

    override suspend fun isUserAuthenticated(): Boolean {
        val isLoggedIn = doesTokenExist()
        if (isLoggedIn) {
            val token = getStoredToken()
            token?.let { RetrofitClient.setAuthToken(it) }
            return getCurrentUser() != null
        }
        return false
    }
}
