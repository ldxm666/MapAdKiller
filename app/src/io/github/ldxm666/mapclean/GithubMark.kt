package io.github.ldxm666.mapclean

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * The official GitHub mark.
 *
 * <p>Drawn from the real SVG path (svgrepo's github-svgrepo-com.svg, viewBox
 * {@code 0 -0.5 25 25}) rather than approximated from circles and triangles. The earlier
 * hand-built version was recognisable but not the real mark, and at header size the
 * difference is obvious.
 *
 * <p>The path is compiled into a flat float array and replayed in [onDraw]. Parsing
 * the SVG at runtime would mean shipping an XML parser path for one icon, and the geometry
 * never changes. The array is {@code opcode, coords...} where 0=move, 1=line, 2=cubic,
 * 3=quadratic, 4=close -- the same shape the SVG commands map onto directly, with
 * relative commands already resolved to absolute during code generation.
 *
 * <p>Coordinates are in the SVG's own 25x25 space and scaled to the view on each draw, so
 * the mark stays sharp at any size and density.
 */
class GithubMark(c: Context) : View(c) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var color = 0xFF171B23.toInt()

    init {
        paint.style = Paint.Style.FILL
    }

    fun setColor(value: Int) {
        color = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = Math.min(w, h)
        if (size <= 0f) return

        val scale = size / VIEW_BOX
        // Centre the 25x25 box in the view. The box starts at y=-0.5, so shift by that too.
        val ox = (w - size) / 2f
        val oy = (h - size) / 2f + 0.5f * scale

        path.reset()
        var i = 0
        while (i < GLYPH.size) {
            val op = GLYPH[i++].toInt()
            when (op) {
                0 -> {
                    path.moveTo(ox + GLYPH[i] * scale, oy + GLYPH[i + 1] * scale)
                    i += 2
                }
                1 -> {
                    path.lineTo(ox + GLYPH[i] * scale, oy + GLYPH[i + 1] * scale)
                    i += 2
                }
                2 -> {
                    path.cubicTo(
                        ox + GLYPH[i] * scale, oy + GLYPH[i + 1] * scale,
                        ox + GLYPH[i + 2] * scale, oy + GLYPH[i + 3] * scale,
                        ox + GLYPH[i + 4] * scale, oy + GLYPH[i + 5] * scale
                    )
                    i += 6
                }
                3 -> {
                    path.quadTo(
                        ox + GLYPH[i] * scale, oy + GLYPH[i + 1] * scale,
                        ox + GLYPH[i + 2] * scale, oy + GLYPH[i + 3] * scale
                    )
                    i += 4
                }
                else -> {
                    path.close()
                }
            }
        }
        // FillType.WINDING is the default and matches the SVG's nonzero fill rule; using
        // EVEN_ODD here would punch holes through the body of the mark.
        path.fillType = Path.FillType.WINDING
        paint.color = color
        canvas.drawPath(path, paint)
    }

    companion object {
        private const val VIEW_BOX = 25f

        /** opcode, then its coordinates. Generated from the SVG path data. */
        private val GLYPH = floatArrayOf(0f, 12.301f, 0f, 1f, 12.394f, 0f, 2f, 14.636f,
                    0f, 16.734f, 0.613f, 18.531f, 1.68f, 1f, 18.476f, 1.649f,
                    2f, 20.347f, 2.743f, 21.862f, 4.258f, 22.925f, 6.071f, 1f,
                    22.956f, 6.129f, 2f, 23.996f, 7.898f, 24.61f, 10.025f, 24.61f,
                    12.295f, 2f, 24.61f, 17.701f, 21.127f, 22.295f, 16.283f, 23.953f,
                    1f, 16.196f, 23.979f, 2f, 16.133f, 23.999f, 16.061f, 24.01f,
                    15.987f, 24.01f, 2f, 15.825f, 24.01f, 15.675f, 23.956f, 15.554f,
                    23.866f, 1f, 15.556f, 23.867f, 2f, 15.428f, 23.752f, 15.348f,
                    23.586f, 15.348f, 23.401f, 2f, 15.348f, 23.396f, 15.348f, 23.391f,
                    15.348f, 23.387f, 1f, 15.348f, 23.388f, 3f, 15.348f, 23.34f,
                    15.356f, 22.162f, 3f, 15.364f, 20.984f, 15.364f, 20.008f, 2f,
                    15.371f, 19.933f, 15.375f, 19.847f, 15.375f, 19.759f, 2f, 15.375f,
                    18.967f, 15.052f, 18.251f, 14.531f, 17.734f, 2f, 15.149f, 17.673f,
                    15.707f, 17.571f, 16.249f, 17.429f, 1f, 16.173f, 17.446f, 2f,
                    16.746f, 17.286f, 17.246f, 17.073f, 17.71f, 16.804f, 1f, 17.679f,
                    16.821f, 2f, 18.187f, 16.541f, 18.617f, 16.185f, 18.971f, 15.763f,
                    1f, 18.977f, 15.756f, 2f, 19.349f, 15.28f, 19.64f, 14.72f,
                    19.817f, 14.111f, 1f, 19.826f, 14.076f, 2f, 20.035f, 13.393f,
                    20.155f, 12.608f, 20.155f, 11.795f, 2f, 20.155f, 11.75f, 20.155f,
                    11.704f, 20.154f, 11.659f, 1f, 20.154f, 11.666f, 2f, 20.154f,
                    11.644f, 20.155f, 11.619f, 20.155f, 11.594f, 2f, 20.155f, 10.346f,
                    19.673f, 9.211f, 18.886f, 8.364f, 1f, 18.889f, 8.367f, 2f,
                    19.057f, 7.927f, 19.154f, 7.419f, 19.154f, 6.888f, 2f, 19.154f,
                    6.239f, 19.009f, 5.625f, 18.75f, 5.074f, 1f, 18.761f, 5.1f,
                    2f, 18.646f, 5.078f, 18.515f, 5.065f, 18.38f, 5.065f, 2f,
                    18.046f, 5.065f, 17.731f, 5.143f, 17.451f, 5.281f, 1f, 17.463f,
                    5.276f, 2f, 16.895f, 5.486f, 16.409f, 5.724f, 15.951f, 6.002f,
                    1f, 15.989f, 5.98f, 1f, 15.38f, 6.364f, 2f, 14.458f,
                    6.1f, 13.399f, 5.948f, 12.305f, 5.948f, 2f, 11.211f, 5.948f,
                    10.152f, 6.1f, 9.148f, 6.384f, 1f, 9.229f, 6.364f, 3f,
                    8.973f, 6.188f, 8.548f, 5.931f, 2f, 8.175f, 5.717f, 7.734f,
                    5.51f, 7.276f, 5.336f, 1f, 7.21f, 5.314f, 2f, 6.917f,
                    5.16f, 6.57f, 5.07f, 6.201f, 5.07f, 2f, 6.077f, 5.07f,
                    5.955f, 5.08f, 5.837f, 5.1f, 1f, 5.85f, 5.098f, 2f,
                    5.602f, 5.622f, 5.457f, 6.237f, 5.457f, 6.886f, 2f, 5.457f,
                    7.417f, 5.554f, 7.926f, 5.732f, 8.395f, 1f, 5.722f, 8.366f,
                    2f, 4.937f, 9.21f, 4.456f, 10.345f, 4.456f, 11.593f, 2f,
                    4.456f, 11.618f, 4.456f, 11.644f, 4.457f, 11.669f, 1f, 4.457f,
                    11.665f, 2f, 4.456f, 11.704f, 4.456f, 11.749f, 4.456f, 11.795f,
                    2f, 4.456f, 12.604f, 4.576f, 13.386f, 4.8f, 14.122f, 1f,
                    4.785f, 14.065f, 2f, 4.974f, 14.708f, 5.261f, 15.267f, 5.635f,
                    15.758f, 1f, 5.626f, 15.745f, 2f, 5.98f, 16.18f, 6.408f,
                    16.538f, 6.893f, 16.807f, 1f, 6.915f, 16.818f, 2f, 7.347f,
                    17.07f, 7.848f, 17.283f, 8.375f, 17.432f, 1f, 8.421f, 17.443f,
                    2f, 8.887f, 17.568f, 9.445f, 17.67f, 10.016f, 17.727f, 1f,
                    10.062f, 17.731f, 2f, 9.631f, 18.159f, 9.344f, 18.731f, 9.278f,
                    19.369f, 1f, 9.277f, 19.381f, 2f, 9.07f, 19.482f, 8.829f,
                    19.564f, 8.578f, 19.617f, 1f, 8.557f, 19.621f, 2f, 8.301f,
                    19.672f, 8.008f, 19.701f, 7.707f, 19.701f, 2f, 7.685f, 19.701f,
                    7.663f, 19.701f, 7.641f, 19.701f, 1f, 7.644f, 19.701f, 2f,
                    7.25f, 19.693f, 6.888f, 19.565f, 6.589f, 19.353f, 1f, 6.595f,
                    19.357f, 2f, 6.224f, 19.098f, 5.924f, 18.762f, 5.714f, 18.371f,
                    1f, 5.707f, 18.356f, 2f, 5.509f, 18.02f, 5.248f, 17.742f,
                    4.939f, 17.529f, 1f, 4.93f, 17.523f, 2f, 4.705f, 17.354f,
                    4.44f, 17.222f, 4.154f, 17.143f, 1f, 4.138f, 17.139f, 1f,
                    3.818f, 17.091f, 2f, 3.795f, 17.089f, 3.768f, 17.088f, 3.741f,
                    17.088f, 2f, 3.601f, 17.088f, 3.468f, 17.116f, 3.347f, 17.165f,
                    1f, 3.354f, 17.162f, 3f, 3.226f, 17.234f, 3.274f, 17.346f,
                    2f, 3.313f, 17.432f, 3.361f, 17.506f, 3.419f, 17.571f, 1f,
                    3.418f, 17.57f, 2f, 3.479f, 17.642f, 3.548f, 17.705f, 3.623f,
                    17.76f, 1f, 3.626f, 17.762f, 1f, 3.738f, 17.842f, 2f,
                    4.021f, 17.99f, 4.254f, 18.196f, 4.431f, 18.445f, 1f, 4.435f,
                    18.451f, 2f, 4.626f, 18.688f, 4.794f, 18.956f, 4.929f, 19.243f,
                    1f, 4.939f, 19.267f, 1f, 5.099f, 19.635f, 2f, 5.234f,
                    20.037f, 5.479f, 20.373f, 5.799f, 20.616f, 1f, 5.804f, 20.62f,
                    2f, 6.104f, 20.854f, 6.466f, 21.022f, 6.861f, 21.098f, 1f,
                    6.877f, 21.1f, 2f, 7.207f, 21.164f, 7.591f, 21.204f, 7.983f,
                    21.212f, 1f, 7.99f, 21.212f, 2f, 8.035f, 21.214f, 8.087f,
                    21.214f, 8.14f, 21.214f, 2f, 8.401f, 21.214f, 8.657f, 21.193f,
                    8.907f, 21.152f, 1f, 8.88f, 21.156f, 1f, 9.248f, 21.092f,
                    3f, 9.248f, 21.701f, 9.256f, 22.51f, 3f, 9.264f, 23.319f,
                    9.264f, 23.383f, 1f, 9.264f, 23.397f, 2f, 9.264f, 23.582f,
                    9.184f, 23.748f, 9.056f, 23.863f, 1f, 9.055f, 23.863f, 2f,
                    8.936f, 23.952f, 8.787f, 24.006f, 8.624f, 24.006f, 2f, 8.549f,
                    24.006f, 8.477f, 23.995f, 8.41f, 23.974f, 1f, 8.415f, 23.975f,
                    2f, 3.486f, 22.286f, 0.006f, 17.692f, 0.006f, 12.285f, 2f,
                    0.006f, 10.017f, 0.618f, 7.892f, 1.687f, 6.066f, 1f, 1.655f,
                    6.124f, 2f, 2.749f, 4.253f, 4.264f, 2.738f, 6.077f, 1.675f,
                    1f, 6.135f, 1.644f, 2f, 7.874f, 0.61f, 9.97f, -0.001f,
                    12.208f, -0.001f, 1f, 12.306f, -0.001f, 1f, 12.301f, -0.001f,
                    4f, 0f, 4.661f, 17.666f, 3f, 4.709f, 17.554f, 4.549f,
                    17.474f, 3f, 4.389f, 17.426f, 4.341f, 17.506f, 3f, 4.293f,
                    17.618f, 4.453f, 17.698f, 3f, 4.597f, 17.794f, 4.661f, 17.666f,
                    4f, 0f, 5.158f, 18.211f, 3f, 5.27f, 18.131f, 5.126f,
                    17.955f, 3f, 4.966f, 17.811f, 4.87f, 17.907f, 3f, 4.758f,
                    17.987f, 4.902f, 18.163f, 3f, 5.061f, 18.32f, 5.158f, 18.21f,
                    4f, 0f, 5.638f, 18.931f, 3f, 5.782f, 18.819f, 5.638f,
                    18.627f, 3f, 5.51f, 18.419f, 5.366f, 18.531f, 3f, 5.222f,
                    18.611f, 5.366f, 18.819f, 3f, 5.51f, 19.027f, 5.638f, 18.931f,
                    4f, 0f, 6.31f, 19.604f, 3f, 6.438f, 19.476f, 6.246f,
                    19.3f, 3f, 6.054f, 19.108f, 5.926f, 19.252f, 3f, 5.782f,
                    19.38f, 5.99f, 19.556f, 3f, 6.182f, 19.748f, 6.31f, 19.6f,
                    4f, 0f, 7.223f, 20.004f, 3f, 7.271f, 19.828f, 7.015f,
                    19.748f, 3f, 6.775f, 19.684f, 6.711f, 19.86f, 3f, 6.647f,
                    20.036f, 6.919f, 20.1f, 3f, 7.159f, 20.197f, 7.223f, 20.004f,
                    4f, 0f, 8.232f, 20.084f, 3f, 8.232f, 19.876f, 7.96f,
                    19.908f, 3f, 7.704f, 19.908f, 7.704f, 20.084f, 3f, 7.704f,
                    20.292f, 7.976f, 20.26f, 3f, 8.232f, 20.261f, 8.232f, 20.085f,
                    4f, 0f, 9.161f, 19.924f, 3f, 9.129f, 19.748f, 8.873f,
                    19.78f, 3f, 8.617f, 19.828f, 8.649f, 20.02f, 3f, 8.681f,
                    20.212f, 8.937f, 20.148f, 3f, 9.193f, 20.084f, 9.162f, 19.924f,
                    4f)
    }
}