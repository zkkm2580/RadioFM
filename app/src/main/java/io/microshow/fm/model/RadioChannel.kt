package io.microshow.fm.model

import java.io.Serializable

data class RadioChannel(
    val id: String,
    val name: String,
    val category: String,
    val frequency: String,
    val streamUrl: String
) : Serializable
