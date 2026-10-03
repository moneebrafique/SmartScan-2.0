package com.smartscan.app.processing

import android.graphics.Bitmap
import com.smartscan.app.data.Adjust
import com.smartscan.app.data.FilterType
import com.smartscan.app.data.Pt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Perspective correction + document enhancement.
 *
 * Colour filters work in Lab space: only lightness gets shadow removal, levels and sharpening,
 * so ink colours stay true. Brightness uses a gamma curve, which brightens paper and mid-tones
 * but never turns dark ink white, so text can't disappear.
 */
object ImageProcessor {

    private data class Preset(
        val shadow: Double,    // 0..1 strength of shadow / uneven-light removal
        val gamma: Double,     // >1 darker mid-tones (bolder ink), <1 lighter
        val contrast: Double,
        val sharpen: Double,
        val chroma: Double,    // colour intensity
        val whiteAt: Double,   // white point relative to paper level (lower = whiter paper)
        val blackAt: Double,   // black point relative to darkest ink
        val auto: Boolean,     // auto levels + white balance
    )

    private fun presetFor(f: FilterType) = when (f) {
        FilterType.MAGIC -> Preset(1.0, 1.15, 0.12, 0.8, 1.3, 0.96, 1.0, true)
        FilterType.LIGHTEN -> Preset(1.0, 0.9, 0.0, 0.4, 1.05, 0.93, 0.9, true)
        FilterType.GRAYSCALE -> Preset(1.0, 1.1, 0.1, 0.7, 0.0, 0.96, 1.0, true)
        else -> Preset(0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0, false)
    }

    // ------------------------------------------------------------------ public API

    /** Full pipeline: crop/warp + filter + adjustments + rotation. */
    fun process(src: Bitmap, corners: List<Pt>, filter: FilterType, adjust: Adjust, rotation: Int): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val warped = warp(rgba, corners)
        rgba.release()
        return finish(warped, filter, adjust, rotation)
    }

    /** Crop/warp only (used to build the live preview base). */
    fun warpBitmap(src: Bitmap, corners: List<Pt>): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val warped = warp(rgba, corners)
        rgba.release()
        return toBitmap(warped)
    }

    /** Filter + adjustments + rotation on an already-warped image (does not modify the input). */
    fun render(warped: Bitmap, filter: FilterType, adjust: Adjust, rotation: Int): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(warped, rgba)
        return finish(rgba, filter, adjust, rotation)
    }

    private fun finish(rgba: Mat, filter: FilterType, adjust: Adjust, rotation: Int): Bitmap {
        val filtered = applyFilter(rgba, filter, adjust)
        if (filtered !== rgba) rgba.release()
        val rotated = rotate(filtered, rotation)
        if (rotated !== filtered) filtered.release()
        return toBitmap(rotated)
    }

    private fun toBitmap(m: Mat): Bitmap {
        val out = Bitmap.createBitmap(m.cols(), m.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(m, out)
        m.release()
        return out
    }

    // ------------------------------------------------------------------ geometry

    private fun warp(rgba: Mat, corners: List<Pt>): Mat {
        val w = rgba.cols().toDouble()
        val h = rgba.rows().toDouble()
        val (tl, tr, br, bl) = DocumentDetector.order(corners).map { Point(it.x * w, it.y * h) }
        val outW = max(dist(tl, tr), dist(bl, br)).roundToInt().coerceAtLeast(16)
        val outH = max(dist(tl, bl), dist(tr, br)).roundToInt().coerceAtLeast(16)
        val srcPts = MatOfPoint2f(tl, tr, br, bl)
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0),
        )
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(
            rgba, out, m, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE,
        )
        srcPts.release(); dstPts.release(); m.release()
        return out
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private fun rotate(m: Mat, degrees: Int): Mat = when (((degrees % 360) + 360) % 360) {
        90 -> Mat().also { Core.rotate(m, it, Core.ROTATE_90_CLOCKWISE) }
        180 -> Mat().also { Core.rotate(m, it, Core.ROTATE_180) }
        270 -> Mat().also { Core.rotate(m, it, Core.ROTATE_90_COUNTERCLOCKWISE) }
        else -> m
    }

    // ------------------------------------------------------------------ filters

    private fun applyFilter(rgba: Mat, filter: FilterType, adj: Adjust): Mat = when {
        filter == FilterType.BW -> blackWhite(rgba, adj)
        filter == FilterType.ORIGINAL && adj == Adjust() -> rgba
        else -> enhance(rgba, filter, adj)
    }

    private fun enhance(rgba: Mat, filter: FilterType, adj: Adjust): Mat {
        val p = presetFor(filter)
        val shadow = (p.shadow + adj.shadows / 100.0).coerceIn(0.0, 1.0)
        val gamma = p.gamma * 2.0.pow(-adj.brightness / 100.0 * 1.3)
        val contrast = p.contrast + adj.contrast / 100.0 * 0.6
        val sharp = (p.sharpen + adj.sharpness / 100.0 * 1.5).coerceIn(0.0, 3.0)
        val chroma = (p.chroma * (1.0 + adj.color / 100.0)).coerceAtLeast(0.0)
        val maxDim = max(rgba.cols(), rgba.rows())

        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        rgb.release()
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        lab.release()
        var l = ch[0]
        val a = ch[1]
        val b = ch[2]

        // 1. Shadow / uneven lighting removal
        if (shadow > 0.0) {
            val bg = estimateBackground(l, a, b)
            val flat = Mat()
            Core.divide(l, bg, flat, 255.0)
            bg.release()
            if (shadow < 1.0) Core.addWeighted(l, 1.0 - shadow, flat, shadow, 0.0, flat)
            l.release()
            l = flat
        }

        // 2. Levels (auto white/black point) + gamma (brightness) + contrast
        var black = 0.0
        var white = 255.0
        if (p.auto) {
            val (paper, ink) = percentiles(l, 50.0, 0.5)
            white = (paper * p.whiteAt).coerceIn(170.0, 254.0)
            black = (ink * p.blackAt).coerceIn(0.0, min(90.0, white - 60.0))
        }
        val lut = toneLut(black, white, gamma, contrast)
        val l2 = Mat()
        Core.LUT(l, lut, l2)
        lut.release()
        l.release()

        // 3. Sharpen text (lightness only, so no colour fringes)
        sharpen(l2, sharp, max(1.0, maxDim / 2500.0))

        if (filter == FilterType.GRAYSCALE) {
            a.release(); b.release()
            return l2
        }

        // 4. Colour: remove chroma noise, white-balance the paper, set colour intensity
        var ca = 128.0
        var cb = 128.0
        var weight: Mat? = null
        if (p.auto) {
            val cs = max(1.0, maxDim / 1500.0)
            Imgproc.GaussianBlur(a, a, Size(0.0, 0.0), cs)
            Imgproc.GaussianBlur(b, b, Size(0.0, 0.0), cs)
            if (shadow > 0.0) {
                val mask = Mat()
                Imgproc.threshold(l2, mask, 235.0, 255.0, Imgproc.THRESH_BINARY)
                if (Core.countNonZero(mask) > mask.total() / 100) {
                    ca = Core.mean(a, mask).`val`[0]
                    cb = Core.mean(b, mask).`val`[0]
                }
                mask.release()
            }
            // Near-white pixels (paper) fade to neutral so the background is pure white.
            val w = Mat()
            l2.convertTo(w, CvType.CV_32F, -1.0 / 40.0, 252.0 / 40.0)
            Core.min(w, Scalar(1.0), w)
            Core.max(w, Scalar(0.0), w)
            weight = w
        }
        scaleChroma(a, ca, weight, chroma)
        scaleChroma(b, cb, weight, chroma)
        weight?.release()

        val merged = Mat()
        Core.merge(listOf(l2, a, b), merged)
        l2.release(); a.release(); b.release()
        val out = Mat()
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2RGB)
        merged.release()
        return out
    }

    private fun scaleChroma(c: Mat, center: Double, weight: Mat?, k: Double) {
        if (weight == null && k == 1.0 && center == 128.0) return
        val f = Mat()
        c.convertTo(f, CvType.CV_32F, 1.0, -center)
        if (weight != null) Core.multiply(f, weight, f, k) else Core.multiply(f, Scalar(k), f)
        f.convertTo(c, CvType.CV_8U, 1.0, 128.0)
        f.release()
    }

    /**
     * Paper brightness at every point. Text is removed with a max filter, then smoothed.
     * Colourful regions (photos, logos) are protected from brightening, and the maximum
     * brightening is capped so dark areas don't turn into noise.
     */
    private fun estimateBackground(l: Mat, a: Mat, b: Mat): Mat {
        val f = (1000.0 / max(l.cols(), l.rows())).coerceAtMost(1.0)
        val s = Mat()
        Imgproc.resize(l, s, Size(), f, f, Imgproc.INTER_AREA)
        var k = max(s.cols(), s.rows()) / 60
        if (k % 2 == 0) k += 1
        k = k.coerceAtLeast(9)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        Imgproc.dilate(s, s, kernel)
        kernel.release()
        Imgproc.medianBlur(s, s, k.coerceAtMost(255))
        val paper = Core.minMaxLoc(s).maxVal

        // Protect colourful areas.
        val sa = Mat(); val sb = Mat()
        Imgproc.resize(a, sa, s.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.resize(b, sb, s.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        val fa = Mat(); val fb = Mat()
        sa.convertTo(fa, CvType.CV_32F, 1.0, -128.0)
        sb.convertTo(fb, CvType.CV_32F, 1.0, -128.0)
        sa.release(); sb.release()
        val mag = Mat()
        Core.magnitude(fa, fb, mag)
        fa.release(); fb.release()
        val colF = Mat()
        Imgproc.threshold(mag, colF, 16.0, 255.0, Imgproc.THRESH_BINARY)
        mag.release()
        val colMask = Mat()
        colF.convertTo(colMask, CvType.CV_8U)
        colF.release()
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(colMask, colMask, Imgproc.MORPH_OPEN, k5)
        k5.release()
        val kk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble()))
        Imgproc.dilate(colMask, colMask, kk)
        kk.release()
        val raised = Mat()
        Core.max(s, Scalar(paper * 0.85), raised)
        raised.copyTo(s, colMask)
        raised.release(); colMask.release()

        Core.max(s, Scalar(max(paper * 0.3, 1.0)), s)
        Imgproc.GaussianBlur(s, s, Size(0.0, 0.0), k * 0.5)
        val bg = Mat()
        Imgproc.resize(s, bg, l.size(), 0.0, 0.0, Imgproc.INTER_CUBIC)
        s.release()
        Core.max(bg, Scalar(1.0), bg)
        return bg
    }

    private fun percentiles(m: Mat, vararg q: Double): DoubleArray {
        val f = (500.0 / max(m.cols(), m.rows())).coerceAtMost(1.0)
        val s = Mat()
        Imgproc.resize(m, s, Size(), f, f, Imgproc.INTER_AREA)
        val n = s.total().toInt()
        val buf = ByteArray(n)
        s.get(0, 0, buf)
        s.release()
        val hist = IntArray(256)
        for (v in buf) hist[v.toInt() and 0xFF]++
        return DoubleArray(q.size) { i ->
            val target = n * q[i] / 100.0
            var acc = 0
            var idx = 255
            for (j in 0 until 256) {
                acc += hist[j]
                if (acc >= target) { idx = j; break }
            }
            idx.toDouble()
        }
    }

    private fun toneLut(black: Double, white: Double, gamma: Double, contrast: Double): Mat {
        val bytes = ByteArray(256)
        val range = (white - black).coerceAtLeast(1.0)
        for (i in 0 until 256) {
            var v = ((i - black) / range).coerceIn(0.0, 1.0).pow(gamma)
            v = (0.5 + (v - 0.5) * (1.0 + contrast)).coerceIn(0.0, 1.0)
            bytes[i] = (v * 255.0).roundToInt().coerceIn(0, 255).toByte()
        }
        val lut = Mat(1, 256, CvType.CV_8U)
        lut.put(0, 0, bytes)
        return lut
    }

    private fun sharpen(m: Mat, amount: Double, sigma: Double) {
        if (amount <= 0.0) return
        val blur = Mat()
        Imgproc.GaussianBlur(m, blur, Size(0.0, 0.0), sigma)
        Core.addWeighted(m, 1.0 + amount, blur, -amount, 0.0, m)
        blur.release()
    }

    // ------------------------------------------------------------------ black & white

    private fun blackWhite(rgba: Mat, adj: Adjust): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val maxDim = max(gray.cols(), gray.rows())

        // Even out lighting
        val f = (800.0 / maxDim).coerceAtMost(1.0)
        val small = Mat()
        Imgproc.resize(gray, small, Size(), f, f, Imgproc.INTER_AREA)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.dilate(small, small, k)
        k.release()
        Imgproc.medianBlur(small, small, 21)
        val bg = Mat()
        Imgproc.resize(small, bg, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        val flat = Mat()
        Core.divide(gray, bg, flat, 255.0)
        gray.release(); bg.release()

        // Denoise, then sharpen edges so letters come out crisp
        val sig = max(0.8, maxDim / 3000.0)
        Imgproc.GaussianBlur(flat, flat, Size(0.0, 0.0), sig * 0.8)
        sharpen(flat, (0.5 + adj.sharpness / 100.0 * 1.5).coerceIn(0.0, 3.0), sig * 1.5)

        var block = maxDim / 50
        if (block % 2 == 0) block += 1
        block = block.coerceAtLeast(15)
        val c = 14.0 - adj.textWeight / 100.0 * 10.0 // higher text weight = bolder/darker text
        val out = Mat()
        Imgproc.adaptiveThreshold(
            flat, out, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY, block, c,
        )
        flat.release()
        removeSpecks(out, max(3, ((maxDim / 1000.0).pow(2) * 3).toInt()))
        return out
    }

    /** Removes isolated black dots smaller than minArea pixels. */
    private fun removeSpecks(bin: Mat, minArea: Int) {
        val inv = Mat()
        Core.bitwise_not(bin, inv)
        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S)
        inv.release(); centroids.release()
        val small = BooleanArray(n) { it != 0 && stats.get(it, Imgproc.CC_STAT_AREA)[0] < minArea }
        stats.release()
        if (small.any { it }) {
            val lab = IntArray(labels.total().toInt())
            labels.get(0, 0, lab)
            val px = ByteArray(bin.total().toInt())
            bin.get(0, 0, px)
            for (i in lab.indices) if (small[lab[i]]) px[i] = 0xFF.toByte()
            bin.put(0, 0, px)
        }
        labels.release()
    }
}
