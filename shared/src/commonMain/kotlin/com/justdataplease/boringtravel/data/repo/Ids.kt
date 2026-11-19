package com.justdataplease.boringtravel.data.repo

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A new row id: a random UUID ("3f2b8c1e-…"), the same shape on both phones. */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()
