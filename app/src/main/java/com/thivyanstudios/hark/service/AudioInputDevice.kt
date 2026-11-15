package com.thivyanstudios.hark.service

data class AudioInputDevice(
    val id: Int,
    val label: String,
    val isBuiltIn: Boolean = false
)
