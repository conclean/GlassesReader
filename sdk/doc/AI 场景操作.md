# AI场景操作

## 1. 监听眼镜端AI事件

CXR-M SDK可以通过fun setAiEventListener()设置AiEventListener监听来自Glasses的AI场景事件。

```kotlin
private val aiEventListener = object : AiEventListener {
    /**
     * When the key long pressed
     */
    override fun onAiKeyDown() {
    }

    /**
     * When the key released, currently their have no effect
     */
    override fun onAiKeyUp() {
    }

    /**
     * When the Ai Scene exit
     */
    override fun onAiExit() {
    }
}

/**
 * Set the AiEventListener
 * @param set true: set the listener, false: remove the listener
 */
fun setAiEventListener(set: Boolean) {
    CxrApi.getInstance().setAiEventListener(if (set) aiEventListener else null)
}
```

## 2. 发送Exit事件给眼镜端

手机端可以主动发送退出AI事件fun sendExitEvent(): ValueUtil.CxrStatus?，退出眼镜端AI场景。

```kotlin
/**
 * Send the exit event to the Ai Scene
 * @return the status of the exit operation
 * @see ValueUtil.CxrStatus
 * @see ValueUtil.CxrStatus.REQUEST_SUCCEED request succeed
 * @see ValueUtil.CxrStatus.REQUEST_WAITING request waiting, do not request again
 * @see ValueUtil.CxrStatus.REQUEST_FAILED request failed
 */
fun sendExitEvent(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().sendExitEvent()
}
```

## 3. 发送ASR内容给眼镜

手机端在获取到ASR结果后，可以通过fun sendAsrContent(content: String): ValueUtil.CxrStatus?将内容推送给眼镜端，当然如果ASR识别结果为空，也需要通过fun notifyAsrNone(): ValueUtil.CxrStatus?通知到眼镜端，同时通过fun notifyAsrError(): ValueUtil.CxrStatus?将ASR错误识别结果通知到眼镜端。最后当ASR识别结束时需要通过fun notifyAsrEnd(): ValueUtil.CxrStatus?通知到眼镜端。

```kotlin
/**
 * Send the asr content to the Ai Scene
 * @param content the content to send
 * @return the status of the send operation
 * @see ValueUtil.CxrStatus
 * @see ValueUtil.CxrStatus.REQUEST_SUCCEED request succeed
 * @see ValueUtil.CxrStatus.REQUEST_WAITING request waiting, do not request again
 * @see ValueUtil.CxrStatus.REQUEST_FAILED request failed
 */
fun sendAsrContent(content: String): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().sendAsrContent(content)
}

/**
 * Notify the Ai Scene that the asr content is none
 * @return the status of the notify operation
 * @see ValueUtil.CxrStatus
 * @see ValueUtil.CxrStatus.REQUEST_SUCCEED request succeed
 * @see ValueUtil.CxrStatus.REQUEST_WAITING request waiting, do not request again
 * @see ValueUtil.CxrStatus.REQUEST_FAILED request failed
 */
fun notifyAsrNone(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyAsrNone()
}

/**
 * Notify the Ai Scene that the asr content is error
 * @return the status of the notify operation
 * @see ValueUtil.CxrStatus
 * @see ValueUtil.CxrStatus.REQUEST_SUCCEED request succeed
 * @see ValueUtil.CxrStatus.REQUEST_WAITING request waiting, do not request again
 * @see ValueUtil.CxrStatus.REQUEST_FAILED request failed
 */
fun notifyAsrError(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyAsrError()
}

/**
 * Notify the Ai Scene that the asr content is end
 * @return the status of the notify operation
 * @see ValueUtil.CxrStatus
 * @see ValueUtil.CxrStatus.REQUEST_SUCCEED request succeed
 * @see ValueUtil.CxrStatus.REQUEST_WAITING request waiting, do not request again
 * @see ValueUtil.CxrStatus.REQUEST_FAILED request failed
 */
fun notifyAsrEnd(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyAsrEnd()
}
```

## 4. AI流程中的相机操作

在AI过程中，如果需要从Glasses的相机中获取实时的图片，可以先使用fun openGlassCamera(width: Int, height: Int, quality: Int): ValueUtil.CxrStatus?(非必须)打开相机，再使用fun takePhoto(width: Int, height: Int, quality: Int, callback: PhotoResultCallback): ValueUtil.CxrStatus?拍照，并在PhotoResultCallback中获取拍摄结果。这里需要注意，quality的取值范围是[0-100]。

```kotlin
// photo result callback
private val result =object :PhotoResultCallback{
    /**
     * photo result callback
     *
     * @param status photo take status
     * @see ValueUtil.CxrStatus
     * @see ValueUtil.CxrStatus.RESPONSE_SUCCEED response succeed
     * @see ValueUtil.CxrStatus.RESPONSE_INVALID response invalid
     * @see ValueUtil.CxrStatus.RESPONSE_TIMEOUT response timeout
     * @param photo WebP photo data byte array
     */
    override fun onPhotoResult( status: ValueUtil.CxrStatus?, photo: ByteArray? ) {
    }
}

/**
 * open ai camera
 *
 * @param width photo width
 * @param height photo height
 * @param quality photo quality range [0-100]
 *
 * @return open camera result
 * @see ValueUtil.CxrStatus
 * @see ValueUtil.CxrStatus.REQUEST_SUCCEED request succeed
 * @see ValueUtil.CxrStatus.REQUEST_WAITING request waiting, do not request again
 * @see ValueUtil.CxrStatus.REQUEST_FAILED request failed
 */
fun aiOpenCamera(width: Int, height: Int, quality: Int): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().openGlassCamera(width, height, quality)
}

/**
 * take photo
 *
 * @param width photo width
 * @param height photo height
 * @param quality photo quality range[0-100]
 *
 * @return take photo result
 * @see ValueUtil.CxrStatus
 * @see ValueUtil.CxrStatus.REQUEST_SUCCEED request succeed
 * @see ValueUtil.CxrStatus.REQUEST_WAITING request waiting, do not request again
 * @see ValueUtil.CxrStatus.REQUEST_FAILED request failed
 */
fun takePhoto(width: Int, height: Int, quality: Int): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().takeGlassPhoto(width, height, quality, result)
}
```

## 5. AI流程中AI返回结果

在将ASR结果和图像传递给AI后，AI会返回结果，通常情况下，应用端会选择使用TTS进行语音播报。可以通过fun sendTTSContent(content: String): ValueUtil.CxrStatus?将AI结果通知到眼镜端，并通过fun notifyTtsAudioFinished(): ValueUtil.CxrStatus?通知眼镜TTS播放结束。

```kotlin
/**
 * Send the tts(Ai Return) content to the Ai Scene
 * @param content the content to send
 * @return the status of the send operation
 * @see ValueUtil.CxrStatus
 * @see ValueUtil.CxrStatus.REQUEST_SUCCEED request succeed
 * @see ValueUtil.CxrStatus.REQUEST_WAITING request waiting, do not request again
 * @see ValueUtil.CxrStatus.REQUEST_FAILED request failed
 */
fun sendTTSContent(content: String): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().sendTtsContent(content)
}

/**
 * Notify the Ai Scene that the tts audio is finished
 * @return the status of the notify operation
 * @see ValueUtil.CxrStatus
 * @see ValueUtil.CxrStatus.REQUEST_SUCCEED request succeed
 * @see ValueUtil.CxrStatus.REQUEST_WAITING request waiting, do not request again
 * @see ValueUtil.CxrStatus.REQUEST_FAILED request failed
 */
fun notifyTtsAudioFinished(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyTtsAudioFinished()
}
```

## 6. AI过程出错处理

无网络：fun notifyNoNetwork(): ValueUtil.CxrStatus?

图片上传错误：fun notifyPicUploadError(): ValueUtil.CxrStatus?

AI请求失败：fun notifyAiError(): ValueUtil.CxrStatus?

```kotlin
/**
 * Notify the Ai Scene that there is no network
 * @return the status of the notify operation
 * @see ValueUtil.CxrStatus
 * @see ValueUtil.CxrStatus.REQUEST_SUCCEED request succeed
 * @see ValueUtil.CxrStatus.REQUEST_WAITING request waiting, do not request again
 * @see ValueUtil.CxrStatus.REQUEST_FAILED request failed
 */
fun notifyNoNetwork(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyNoNetwork()
}

/**
 * Notify the Ai Scene that the pic upload error
 * @return the status of the notify operation
 * @see ValueUtil.CxrStatus
 * @see ValueUtil.CxrStatus.REQUEST_SUCCEED request succeed
 * @see ValueUtil.CxrStatus.REQUEST_WAITING request waiting, do not request again
 * @see ValueUtil.CxrStatus.REQUEST_FAILED request failed
 */
fun notifyPicUploadError(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyPicUploadError()
}

/**
 * Notify the Ai Scene that the ai error
 * @return the status of the notify operation
 * @see ValueUtil.CxrStatus
 * @see ValueUtil.CxrStatus.REQUEST_SUCCEED request succeed
 * @see ValueUtil.CxrStatus.REQUEST_WAITING request waiting, do not request again
 * @see ValueUtil.CxrStatus.REQUEST_FAILED request failed
 */
fun notifyAiError(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyAiError()
}
```