package com.commcrete.stardust.request_objects.model.license

data class LicenseX(
    val __v: Int,
    val _id: String,
    val createdAt: String,
    val licenseType: String,
    val maximumCallMinutes: Int,
    val maximumNumberOfMessages: Int,
    val price: Int,
    val updatedAt: String
)