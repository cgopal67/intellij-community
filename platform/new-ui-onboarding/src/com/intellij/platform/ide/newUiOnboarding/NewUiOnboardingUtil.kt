// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.newUiOnboarding

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.ApiStatus
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import javax.swing.SwingUtilities

@ApiStatus.Internal
object NewUiOnboardingUtil {
  private const val LOTTIE_SCRIPT_PATH = "newUiOnboarding/lottie.js"
  private const val LOTTIE_HTML_PATH = "newUiOnboarding/lottiePage.html"
  private const val LOTTIE_SCRIPT_PLACEHOLDER = "{{lottieScript}}"
  private const val LOTTIE_JSON_PLACEHOLDER = "{{lottieJson}}"

  inline fun <reified T : Component> findUiComponent(project: Project, predicate: (T) -> Boolean): T? {
    val root = WindowManager.getInstance().getFrame(project) ?: return null
    findUiComponent(root, predicate)?.let { return it }
    for (window in root.ownedWindows) {
      findUiComponent(window, predicate)?.let { return it }
    }
    return null
  }

  inline fun <reified T : Component> findUiComponent(root: Component, predicate: (T) -> Boolean): T? {
    val component = UIUtil.uiTraverser(root).find {
      it is T && it.isVisible && it.isShowing && predicate(it)
    }
    return component as? T
  }

  fun convertPointToFrame(project: Project, source: Component, point: Point): RelativePoint? {
    val frame = WindowManager.getInstance().getFrame(project) ?: return null
    val framePoint = SwingUtilities.convertPoint(source, point, frame)
    return RelativePoint(frame, framePoint)
  }

  /**
   * Creates an HTML page with provided lottie animation
   * @return a pair of html page text and a size of the animation
   */
  fun createLottieAnimationPage(lottieJsonPath: String, classLoader: ClassLoader): Pair<String, Dimension>? {
    // read json using provided class loader, because it can be in the plugin jar
    val lottieJson = readResourceOrLog(lottieJsonPath, classLoader) ?: return null
    // read html page and js script by platform class loader, because they are in the platform jar
    val lottiePage = readResourceOrLog(LOTTIE_HTML_PATH, NewUiOnboardingUtil::class.java.classLoader) ?: return null
    val lottieScript = readResourceOrLog(LOTTIE_SCRIPT_PATH, NewUiOnboardingUtil::class.java.classLoader) ?: return null
    val size = getLottieImageSize(lottieJson) ?: return null

    val scriptIndex = lottiePage.indexOf(LOTTIE_SCRIPT_PLACEHOLDER)
    val resultingPage = StringBuilder(lottiePage.length + lottieScript.length + lottieJson.length)
      .append(lottiePage)
      .replace(scriptIndex, scriptIndex + LOTTIE_SCRIPT_PLACEHOLDER.length, lottieScript)
    val jsonIndex = resultingPage.indexOf(LOTTIE_JSON_PLACEHOLDER)
    resultingPage.replace(jsonIndex, jsonIndex + LOTTIE_JSON_PLACEHOLDER.length, lottieJson)
    return resultingPage.toString() to size
  }

  private fun readResourceOrLog(path: String, classLoader: ClassLoader): String? {
    return try {
      val url = classLoader.getResource(path) ?: run {
        LOG.error("Failed to find resource by path: $path")
        return null
      }
      url.readText()
    }
    catch (t: Throwable) {
      LOG.error("Failed to read resource by path: $path", t)
      null
    }
  }

  private fun getLottieImageSize(lottieJson: String): Dimension? {
    val json = Json { ignoreUnknownKeys = true }
    return try {
      val size = json.decodeFromString<LottieImageSize>(lottieJson)
      Dimension(size.width, size.height)
    }
    catch (t: Throwable) {
      LOG.error("Failed to parse lottie json", t)
      null
    }
  }

  @Serializable
  private data class LottieImageSize(
    @SerialName("w") val width: Int,
    @SerialName("h") val height: Int
  )

  private val LOG = thisLogger()
}