# JisuYatu-SolidBgCut

极速压图 (JisuYatu) 纯色背景去背景算法  
**纯 Kotlin/Java 实现 · 无平台依赖 · 可跨 Android / iOS / Windows / Linux / Web / 后端 复用**

适用场景：证件照、商品图、直播封面等 **纯色 / 接近纯色背景** 的图片，一键抠出前景主体并生成透明背景 PNG。

---

## 1. 核心算法与 API

文件：`src/PureBackgroundRemover.kt`  
包名示例：

```kotlin
package com.jisuyatu.solidbgcut
```

核心类：

```kotlin
class PureBackgroundRemover {

    /**
     * 移除纯色背景（增强版）
     *
     * @param pixels 图片像素数组 (ARGB)，长度 = width * height
     * @param width  图片宽度
     * @param height 图片高度
     * @param backgroundColor 背景颜色 (ARGB)，为 null 时自动检测背景色
     * @param tolerance 颜色容差 [0, 255]，默认 30，越大越宽松
     * @return 处理后的像素数组 (ARGB，背景已透明)
     */
    fun removeBackground(
        pixels: IntArray,
        width: Int,
        height: Int,
        backgroundColor: Int? = null,
        tolerance: Int = 30
    ): IntArray
}
```

### 算法思路（简要）

1. **边缘采样 + 聚类检测背景色**  
   从图像四周多行多列采样像素，做简易聚类，得到主导背景颜色。
2. **按颜色距离生成 mask**  
   使用感知颜色距离（人眼对 R/G/B 敏感度不同）计算每个像素与背景的距离：  
   - 距离小 → 认为是背景（透明）  
   - 距离大 → 认为是前景（不透明）  
   - 中间区域做渐变过渡，保证边缘自然。
3. **形态学操作去噪 / 填洞**  
   通过腐蚀 / 膨胀等操作去掉小噪点、填补小空洞。
4. **连通域分析保护前景**  
   通过连通域分析避免把主体中的细节误当成背景删掉。
5. **边缘平滑 + 二次清理**  
   对边缘半透明像素做平滑，进一步清理残留的背景色，生成更自然的透明边缘。

> 算法实现全部在 `PureBackgroundRemover.kt` 中，可以直接阅读源码学习细节。

---

## 2. Android 调用示例

以 `Bitmap` 为例：

```kotlin
import android.graphics.Bitmap
import com.jisuyatu.solidbgcut.PureBackgroundRemover

fun removeSolidBgFromBitmap(input: Bitmap): Bitmap {
    require(input.config == Bitmap.Config.ARGB_8888) {
        "Bitmap config must be ARGB_8888"
    }

    val width = input.width
    val height = input.height
    val pixels = IntArray(width * height)
    input.getPixels(pixels, 0, width, 0, 0, width, height)

    val remover = PureBackgroundRemover()
    val resultPixels = remover.removeBackground(
        pixels = pixels,
        width = width,
        height = height,
        backgroundColor = null, // 让算法自动检测背景色
        tolerance = 30          // 可按需求微调
    )

    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    output.setPixels(resultPixels, 0, width, 0, 0, width, height)
    return output
}
```

> 建议在 `Dispatchers.Default/IO` 上调用，避免阻塞主线程。

---

## 3. iOS 调用方式

### 3.1 Kotlin Multiplatform + Kotlin/Native（推荐）

1. 把 `PureBackgroundRemover.kt` 放到一个 **Kotlin Multiplatform** 工程的 `commonMain`。
2. 通过 Kotlin/Native 生成 iOS Framework（例如 `SolidBgCutKit.framework`）。
3. 在 Swift 中调用：

```swift
import SolidBgCutKit

func removeSolidBgFromUIImage(_ image: UIImage) -> UIImage? {
    guard let cgImage = image.cgImage else { return nil }

    let width = cgImage.width
    let height = cgImage.height
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    var rawData = [UInt8](repeating: 0, count: width * height * 4)

    guard let ctx = CGContext(
        data: &rawData,
        width: width,
        height: height,
        bitsPerComponent: 8,
        bytesPerRow: 4 * width,
        space: colorSpace,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return nil }

    ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

    // RGBA -> ARGB IntArray
    let argb = rgbaBytesToArgbInts(rawData)

    let remover = PureBackgroundRemover()
    let result = remover.removeBackground(
        pixels: KotlinIntArray.from(argb),
        width: Int32(width),
        height: Int32(height),
        backgroundColor: nil,
        tolerance: 30
    )

    let outRgba = argbIntsToRgbaBytes(result)
    // 再构造 UIImage 返回
    ...
}
```

> `rgbaBytesToArgbInts` / `argbIntsToRgbaBytes` 是简单的字节重排工具函数，可在项目中自行实现。

### 3.2 直接用 Swift 重写一份

算法只依赖整型数组和数学运算，也可以根据 `PureBackgroundRemover.kt` 直接在 Swift 中实现同样逻辑，无需 Kotlin/Native。

---

## 4. Windows / Linux 调用（JVM）

基于 JVM 的 Kotlin/Java 示例：

```kotlin
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.File
import com.jisuyatu.solidbgcut.PureBackgroundRemover

fun main() {
    val input = ImageIO.read(File("input.png"))
    val width = input.width
    val height = input.height
    val pixels = IntArray(width * height)
    input.getRGB(0, 0, width, height, pixels, 0, width)

    val remover = PureBackgroundRemover()
    val result = remover.removeBackground(pixels, width, height, null, 30)

    val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    output.setRGB(0, 0, width, height, result, 0, width)
    ImageIO.write(output, "png", File("output.png"))
}
```

---

## 5. Web 前端（纯前端）调用

### 5.1 Kotlin/JS 打包为 NPM 包

将本库作为 Kotlin/JS 项目编译为 JS 库或 NPM 包，例如导出一个 `PureBackgroundRemover`：

```javascript
import { PureBackgroundRemover } from "jisuyatu-solidbgcut-js";

const imageData = ctx.getImageData(0, 0, width, height);
const rgba = imageData.data; // Uint8ClampedArray

// RGBA -> ARGB IntArray
const argb = rgbaToArgbIntArray(rgba);

const remover = new PureBackgroundRemover();
const outArgb = remover.removeBackground(argb, width, height, null, 30);

// ARGB -> RGBA
const outRgba = argbIntArrayToRgba(outArgb);

const outImageData = new ImageData(outRgba, width, height);
ctx.putImageData(outImageData, 0, 0);
```

### 5.2 直接用 TypeScript/JavaScript 改写

也可以将 `PureBackgroundRemover.kt` 中的逻辑翻译成 TypeScript/JavaScript，操作 `Uint8ClampedArray` 即可，无需依赖 Kotlin 运行时。

---

## 6. 后端服务封装示例（Java/Kotlin）

以 Spring Boot 为例：

```kotlin
import org.springframework.web.bind.annotation.*
import java.util.Base64
import javax.imageio.ImageIO
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import com.jisuyatu.solidbgcut.PureBackgroundRemover

@RestController
class SolidBgController {

    @PostMapping("/api/solid-bg-cut")
    fun solidBgCut(@RequestBody body: ImageRequest): ImageResponse {
        val bytes = Base64.getDecoder().decode(body.base64Png)
        val input = ImageIO.read(bytes.inputStream())

        val width = input.width
        val height = input.height
        val pixels = IntArray(width * height)
        input.getRGB(0, 0, width, height, pixels, 0, width)

        val remover = PureBackgroundRemover()
        val result = remover.removeBackground(pixels, width, height, null, 30)

        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        output.setRGB(0, 0, width, height, result, 0, width)

        val outBaos = ByteArrayOutputStream()
        ImageIO.write(output, "png", outBaos)
        val outBase64 = Base64.getEncoder().encodeToString(outBaos.toByteArray())

        return ImageResponse(base64Png = outBase64)
    }
}

data class ImageRequest(val base64Png: String)
data class ImageResponse(val base64Png: String)
```

---

## 7. 许可证

本仓库使用 Apache-2.0 许可证（见 `LICENSE` 文件）。  
你可以在商业项目中使用本算法，但需保留版权和许可证声明。

