package com.samrat.cardboardhands

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.Objdetect
import kotlin.math.sqrt

/**
 * Tracks the Joy-Con by printed ArUco markers (markers/joycon_markers_A4.pdf): full position and
 * rotation, twist included. IDs 0-3 sit on the left Joy-Con, 4-7 on the right, in the order
 * left side, middle (button face), right side, top.
 *
 * Neutral pose: the button face looks at the camera and the Joy-Con top points up; the controller
 * then points forward. Each other face is that pose turned by 90 degrees.
 */
class JoyConMarkers {
    private val ready = OpenCVLoader.initLocal()
    private val detector = if (ready) ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)) else null
    private val rgba = Mat()
    private val gray = Mat()
    private val half = MARKER_SIZE_M / 2
    private val objectPoints = MatOfPoint3f(
        Point3(-half, half, 0.0), Point3(half, half, 0.0), Point3(half, -half, 0.0), Point3(-half, -half, 0.0)
    )
    private val previous = arrayOf(MarkerPose(), MarkerPose())

    val available get() = detector != null

    /** Left and right Joy-Con in an upright frame. */
    fun process(frame: Bitmap): Pair<MarkerPose, MarkerPose> {
        val detector = detector ?: return MarkerPose() to MarkerPose()
        Utils.bitmapToMat(frame, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val corners = ArrayList<Mat>()
        val ids = Mat()
        detector.detectMarkers(gray, corners, ids)

        val focal = frame.width * FOCAL_PER_WIDTH
        val camera = Mat.eye(3, 3, CvType.CV_64F).apply {
            put(0, 0, focal); put(1, 1, focal)
            put(0, 2, frame.width / 2.0); put(1, 2, frame.height / 2.0)
        }
        val distortion = MatOfDouble(0.0, 0.0, 0.0, 0.0)
        // Per hand keep the biggest marker: it is the most face-on and gives the steadiest pose.
        val best = arrayOfNulls<Pair<Double, MarkerPose>>(2)
        for (i in 0 until ids.rows()) {
            val id = ids.get(i, 0)[0].toInt()
            if (id !in 0..7) continue
            val hand = id / 4
            val points = MatOfPoint2f(corners[i].reshape(2, 4))
            val area = Imgproc.contourArea(points)
            val rvec = Mat()
            val tvec = Mat()
            if (!Calib3d.solvePnP(objectPoints, points, camera, distortion, rvec, tvec, false, Calib3d.SOLVEPNP_IPPE_SQUARE)) {
                continue
            }
            val centre = points.toArray()
            val cx = centre.sumOf { it.x } / 4
            val cy = centre.sumOf { it.y } / 4
            val q = controllerRotation(rvec, id % 4)
            val t = DoubleArray(3).also { tvec.get(0, 0, it) }
            val pose = MarkerPose(
                found = true,
                x = (cx / frame.width).toFloat(),
                y = (cy / frame.height).toFloat(),
                distance = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]).toFloat(),
                qx = q[0], qy = q[1], qz = q[2], qw = q[3],
                markerId = id
            )
            if (best[hand] == null || area > best[hand]!!.first) best[hand] = area to pose
        }
        return smooth(0, best[0]?.second ?: MarkerPose()) to smooth(1, best[1]?.second ?: MarkerPose())
    }

    /** Marker rotation (OpenCV camera axes) to controller rotation (OpenXR axes). */
    private fun controllerRotation(rvec: Mat, face: Int): FloatArray {
        val r = Mat()
        Calib3d.Rodrigues(rvec, r)
        val m = Array(3) { row -> DoubleArray(3) { col -> r.get(row, col)[0] } }
        // OpenCV camera axes (x right, y down, z forward) to OpenXR (x right, y up, z back). The marker's
        // own axes already are x right, y up, z out of the paper, so only the camera side flips.
        val flip = doubleArrayOf(1.0, -1.0, -1.0)
        val xr = Array(3) { row -> DoubleArray(3) { col -> m[row][col] * flip[row] } }
        val marker = quaternion(xr)
        // Joy-Con body from the face the marker is on, then body "up" becomes controller "forward".
        val body = multiply(marker, FACE_TO_BODY[face])
        return multiply(body, UP_TO_FORWARD)
    }

    private fun smooth(slot: Int, raw: MarkerPose): MarkerPose {
        val last = previous[slot]
        if (!raw.found || !last.found) {
            if (raw.found) previous[slot] = raw
            return raw
        }
        // Quaternions q and -q are the same rotation: keep the one closest to the last.
        val sign = if (raw.qx * last.qx + raw.qy * last.qy + raw.qz * last.qz + raw.qw * last.qw < 0) -1f else 1f
        val k = SMOOTHING
        var qx = last.qx + (raw.qx * sign - last.qx) * k
        var qy = last.qy + (raw.qy * sign - last.qy) * k
        var qz = last.qz + (raw.qz * sign - last.qz) * k
        var qw = last.qw + (raw.qw * sign - last.qw) * k
        val length = sqrt(qx * qx + qy * qy + qz * qz + qw * qw).coerceAtLeast(1e-4f)
        qx /= length; qy /= length; qz /= length; qw /= length
        val result = raw.copy(
            x = last.x + (raw.x - last.x) * k,
            y = last.y + (raw.y - last.y) * k,
            distance = last.distance + (raw.distance - last.distance) * k,
            qx = qx, qy = qy, qz = qz, qw = qw
        )
        previous[slot] = result
        return result
    }

    companion object {
        /** Side of the black square as printed by markers/make_markers.py. */
        const val MARKER_SIZE_M = 0.025
        /** Focal length as a share of image width; a typical phone main camera, about 70 degrees wide. */
        private const val FOCAL_PER_WIDTH = 0.72
        private const val SMOOTHING = .35f

        private val S = sqrt(.5).toFloat()
        /** Rotation from a face's marker frame to the Joy-Con body frame (face order: left, middle, right, top). */
        private val FACE_TO_BODY = arrayOf(
            floatArrayOf(0f, S, 0f, S),   // left side: turned 90 degrees about up
            floatArrayOf(0f, 0f, 0f, 1f), // button face
            floatArrayOf(0f, -S, 0f, S),  // right side
            floatArrayOf(S, 0f, 0f, S),   // top: tilted 90 degrees about right
        )
        /** Controller forward (-Z) lies along body up (+Y): 90 degrees about X. */
        private val UP_TO_FORWARD = floatArrayOf(S, 0f, 0f, S)

        private fun multiply(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        )

        private fun quaternion(m: Array<DoubleArray>): FloatArray {
            val trace = m[0][0] + m[1][1] + m[2][2]
            val q = DoubleArray(4)
            if (trace > 0) {
                val s = 0.5 / sqrt(trace + 1.0)
                q[3] = 0.25 / s
                q[0] = (m[2][1] - m[1][2]) * s
                q[1] = (m[0][2] - m[2][0]) * s
                q[2] = (m[1][0] - m[0][1]) * s
            } else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
                val s = 2.0 * sqrt(1.0 + m[0][0] - m[1][1] - m[2][2])
                q[0] = 0.25 * s
                q[1] = (m[1][0] + m[0][1]) / s
                q[2] = (m[0][2] + m[2][0]) / s
                q[3] = (m[2][1] - m[1][2]) / s
            } else if (m[1][1] > m[2][2]) {
                val s = 2.0 * sqrt(1.0 + m[1][1] - m[0][0] - m[2][2])
                q[0] = (m[1][0] + m[0][1]) / s
                q[1] = 0.25 * s
                q[2] = (m[2][1] + m[1][2]) / s
                q[3] = (m[0][2] - m[2][0]) / s
            } else {
                val s = 2.0 * sqrt(1.0 + m[2][2] - m[0][0] - m[1][1])
                q[0] = (m[0][2] + m[2][0]) / s
                q[1] = (m[2][1] + m[1][2]) / s
                q[2] = 0.25 * s
                q[3] = (m[1][0] - m[0][1]) / s
            }
            return floatArrayOf(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat())
        }
    }
}
