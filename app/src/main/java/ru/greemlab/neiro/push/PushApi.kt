package ru.greemlab.neiro.push

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface PushApi {

    @POST("v1/devices/register")
    suspend fun registerDevice(
        @Header("Authorization") authorization: String,
        @Body body: RegisterDeviceRequest,
    ): Response<RegisterDeviceResponse>

    @DELETE("v1/devices/{deviceId}")
    suspend fun unregisterDevice(
        @Header("Authorization") authorization: String,
        @Path("deviceId") deviceId: String,
    ): Response<Unit>
}

data class RegisterDeviceRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("fcm_token") val fcmToken: String,
    @SerializedName("company_id") val companyId: Int,
    @SerializedName("staff_id") val staffId: Int,
    @SerializedName("partner_token") val partnerToken: String,
    @SerializedName("user_token") val userToken: String,
    val label: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
)

data class RegisterDeviceResponse(
    @SerializedName("account_id") val accountId: Int,
    @SerializedName("device_id") val deviceId: String,
)
