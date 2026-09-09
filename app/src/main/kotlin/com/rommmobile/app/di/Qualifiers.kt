package com.rommmobile.app.di

import javax.inject.Qualifier

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApiClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DownloadClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ImageClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
