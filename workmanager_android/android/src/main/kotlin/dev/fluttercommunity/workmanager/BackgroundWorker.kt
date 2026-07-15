package dev.fluttercommunity.workmanager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import dev.fluttercommunity.workmanager.pigeon.TaskStatus
import dev.fluttercommunity.workmanager.pigeon.WorkmanagerFlutterApi
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation
import java.util.Random

/**
 * A simple worker that posts your input back to your Flutter application.
 *
 * It will block the background thread until a value of either true or false is received back from Flutter code.
 */
class BackgroundWorker(
    applicationContext: Context,
    private val workerParams: WorkerParameters,
) : ListenableWorker(applicationContext, workerParams) {
    private lateinit var flutterApi: WorkmanagerFlutterApi

    companion object {
        const val PAYLOAD_KEY = "dev.fluttercommunity.workmanager.INPUT_DATA"
        const val DART_TASK_KEY = "dev.fluttercommunity.workmanager.DART_TASK"
        const val FOREGROUND_CHANNEL_NAME = "dev.fluttercommunity.workmanager/foreground"

        private val flutterLoader = FlutterLoader()
    }

    private val payload
        get() =
            workerParams.inputData.keyValueMap
                .filter { it.key.startsWith("payload_") }
                .mapKeys { it.key.replace("payload_", "") }
                .mapValues {
                    when (it.value) {
                        is Array<*> -> (it.value as Array<*>).asList()
                        else -> it.value
                    }
                }

    private val dartTask
        get() = workerParams.inputData.getString(DART_TASK_KEY)

    private val runAttemptCount = workerParams.runAttemptCount
    private val randomThreadIdentifier = Random().nextInt()
    private var engine: FlutterEngine? = null
    private var foregroundChannel: MethodChannel? = null

    private var startTime: Long = 0

    private var completer: CallbackToFutureAdapter.Completer<Result>? = null

    private var resolvableFuture =
        CallbackToFutureAdapter.getFuture { completer ->
            this.completer = completer
            null
        }

    override fun startWork(): ListenableFuture<Result> {
        startTime = System.currentTimeMillis()

        engine = FlutterEngine(applicationContext)

        if (!flutterLoader.initialized()) {
            flutterLoader.startInitialization(applicationContext)
        }

        flutterLoader.ensureInitializationCompleteAsync(
            applicationContext,
            null,
            Handler(Looper.getMainLooper()),
        ) {
            val callbackHandle = SharedPreferenceHelper.getCallbackHandle(applicationContext)
            val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)

            if (callbackInfo == null) {
                val exception = IllegalStateException("Failed to resolve Dart callback for handle $callbackHandle")
                WorkmanagerDebug.onExceptionEncountered(applicationContext, null, exception)
                completer?.set(Result.failure())
                return@ensureInitializationCompleteAsync
            }

            val localDartTask = dartTask

            if (localDartTask == null) {
                val exception = IllegalStateException("Dart task is null")
                WorkmanagerDebug.onExceptionEncountered(applicationContext, null, exception)
                completer?.set(Result.failure())
                return@ensureInitializationCompleteAsync
            }

            val dartBundlePath = flutterLoader.findAppBundlePath()

            val taskInfo =
                TaskDebugInfo(
                    taskName = localDartTask,
                    inputData = payload,
                    startTime = startTime,
                    callbackHandle = callbackHandle,
                    callbackInfo = callbackInfo?.callbackName,
                )

            val startStatus = if (runAttemptCount > 0) TaskStatus.RETRYING else TaskStatus.STARTED
            WorkmanagerDebug.onTaskStatusUpdate(applicationContext, taskInfo, startStatus)

            engine?.let { engine ->
                flutterApi = WorkmanagerFlutterApi(engine.dartExecutor.binaryMessenger)
                foregroundChannel =
                    MethodChannel(engine.dartExecutor.binaryMessenger, FOREGROUND_CHANNEL_NAME).apply {
                        setMethodCallHandler { call, result ->
                            if (call.method != "setForeground") {
                                result.notImplemented()
                                return@setMethodCallHandler
                            }
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val options = call.arguments as Map<String, Any?>
                                setForegroundAsync(createForegroundInfo(options))
                                result.success(null)
                            } catch (error: Exception) {
                                result.error("foreground_worker", error.message, null)
                            }
                        }
                    }

                engine.dartExecutor.executeDartCallback(
                    DartExecutor.DartCallback(
                        applicationContext.assets,
                        dartBundlePath,
                        callbackInfo,
                    ),
                )

                // Initialize the background channel
                flutterApi.backgroundChannelInitialized {
                    // Channel is initialized, now execute the task
                    executeBackgroundTask()
                }
            }
        }

        return resolvableFuture
    }

    override fun onStopped() {
        stopEngine(null)
    }

    private fun stopEngine(
        result: Result?,
        errorMessage: String? = null,
    ) {
        val fetchDuration = System.currentTimeMillis() - startTime

        val localDartTask = dartTask

        if (localDartTask == null) {
            val exception = IllegalStateException("Dart task is null")
            WorkmanagerDebug.onExceptionEncountered(applicationContext, null, exception)
            completer?.set(Result.failure())
            return
        }

        val taskInfo =
            TaskDebugInfo(
                taskName = localDartTask,
                inputData = payload,
                startTime = startTime,
            )

        val taskResult =
            TaskResult(
                success = result is Result.Success,
                duration = fetchDuration,
                error =
                    when (result) {
                        is Result.Failure -> errorMessage ?: "Task failed"
                        else -> null
                    },
            )

        val status =
            when (result) {
                is Result.Success -> TaskStatus.COMPLETED
                is Result.Retry -> TaskStatus.RESCHEDULED
                else -> TaskStatus.FAILED
            }
        WorkmanagerDebug.onTaskStatusUpdate(applicationContext, taskInfo, status, taskResult)

        // No result indicates we were signalled to stop by WorkManager.  The result is already
        // STOPPED, so no need to resolve another one.
        if (result != null) {
            this.completer?.set(result)
        }

        // If stopEngine is called from `onStopped`, it may not be from the main thread.
        Handler(Looper.getMainLooper()).post {
            foregroundChannel?.setMethodCallHandler(null)
            foregroundChannel = null
            engine?.destroy()
            engine = null
        }
    }

    private fun executeBackgroundTask() {
        // Convert payload to the format expected by Pigeon (Map<String?, Object?>)
        val pigeonPayload = payload.mapKeys { it.key as String? }.mapValues { it.value as Object? }

        val localDartTask = dartTask

        if (localDartTask == null) {
            val exception = IllegalStateException("Dart task is null")
            WorkmanagerDebug.onExceptionEncountered(applicationContext, null, exception)

            stopEngine(Result.failure(), exception.message)
            return
        }

        flutterApi.executeTask(localDartTask, pigeonPayload) { result ->
            when {
                result.isSuccess -> {
                    val wasSuccessful = result.getOrNull() ?: false
                    stopEngine(if (wasSuccessful) Result.success() else Result.retry())
                }
                result.isFailure -> {
                    val exception = result.exceptionOrNull()
                    // Don't call onExceptionEncountered for Dart task failures
                    // These are handled as normal failures via onTaskStatusUpdate
                    stopEngine(Result.failure(), exception?.message)
                }
            }
        }
    }

    private fun createForegroundInfo(options: Map<String, Any?>): ForegroundInfo {
        val notificationId = options.requireInt("notificationId")
        val channelId = options.requireString("channelId")
        val channelName = options.requireString("channelName")
        val channelDescription = options.requireString("channelDescription")
        val serviceType = options.requireInt("foregroundServiceType")
        val progress = options["progress"] as? Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = channelDescription
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(false)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                }
            applicationContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notificationBuilder =
            NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(resolveSmallIcon(options))
                .setContentTitle(options.requireString("title"))
                .setContentText(options.requireString("description"))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setLocalOnly(true)
                .setShowWhen(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
                .setProgress(100, progress ?: 0, progress == null)

        (options["cancelLabel"] as? String)?.takeIf { it.isNotBlank() }?.let { cancelLabel ->
            val cancelIntent =
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            notificationBuilder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                cancelLabel,
                cancelIntent,
            )
        }

        val notification = notificationBuilder.build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, serviceType)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun Map<String, Any?>.requireString(key: String): String =
        this[key] as? String ?: throw IllegalArgumentException("Missing $key")

    private fun Map<String, Any?>.requireInt(key: String): Int =
        (this[key] as? Number)?.toInt() ?: throw IllegalArgumentException("Missing $key")

    private fun resolveSmallIcon(options: Map<String, Any?>): Int {
        val resourceName = options["smallIconResourceName"] as? String
        if (!resourceName.isNullOrBlank()) {
            val resourceId =
                applicationContext.resources.getIdentifier(
                    resourceName,
                    "drawable",
                    applicationContext.packageName,
                )
            if (resourceId != 0) return resourceId
        }
        return applicationContext.applicationInfo.icon
    }
}
