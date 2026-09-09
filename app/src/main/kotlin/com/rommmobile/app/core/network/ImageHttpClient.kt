package com.rommmobile.app.core.network

import com.rommmobile.app.di.ImageClient
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Thin holder so the Application can hand Coil an OkHttp client from the Hilt graph. */
@Singleton
class ImageHttpClient @Inject constructor(@ImageClient val client: OkHttpClient)
