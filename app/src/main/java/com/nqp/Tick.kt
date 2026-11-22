package com.nqp

import android.content.Context
import androidx.work.*

class Tick(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result { Store.init(applicationContext); try { Engine.tick(applicationContext) } catch (e: Exception) {}; return Result.success() }
}
