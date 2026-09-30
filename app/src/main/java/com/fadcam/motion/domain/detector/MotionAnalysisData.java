package com.fadcam.motion.domain.detector;

import android.media.Image;
import java.nio.ByteBuffer;

/**
 * Native-free frame and event data retained for the existing recording index.
 * The personal app does not run AI detection or automatic motion recording.
 */
public final class MotionAnalysisData {
    private MotionAnalysisData() {}

    public static final class FramePacket {
        public final int width;
        public final int height;
        public final int yRowStride;
        public final int yPixelStride;
        public final int uvRowStride;
        public final int uvPixelStride;
        public final byte[] y;
        public final byte[] u;
        public final byte[] v;

        private FramePacket(
                int width,
                int height,
                int yRowStride,
                int yPixelStride,
                int uvRowStride,
                int uvPixelStride,
                byte[] y,
                byte[] u,
                byte[] v
        ) {
            this.width = width;
            this.height = height;
            this.yRowStride = yRowStride;
            this.yPixelStride = yPixelStride;
            this.uvRowStride = uvRowStride;
            this.uvPixelStride = uvPixelStride;
            this.y = y;
            this.u = u;
            this.v = v;
        }

        public static FramePacket copyFrom(Image image) {
            if (image == null || image.getPlanes() == null || image.getPlanes().length < 3) {
                return null;
            }
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();
            if (yBuffer == null || uBuffer == null || vBuffer == null) {
                return null;
            }

            ByteBuffer yDup = yBuffer.duplicate();
            ByteBuffer uDup = uBuffer.duplicate();
            ByteBuffer vDup = vBuffer.duplicate();
            yDup.rewind();
            uDup.rewind();
            vDup.rewind();

            byte[] y = new byte[yDup.remaining()];
            byte[] u = new byte[uDup.remaining()];
            byte[] v = new byte[vDup.remaining()];
            yDup.get(y);
            uDup.get(u);
            vDup.get(v);

            return new FramePacket(
                    image.getWidth(),
                    image.getHeight(),
                    planes[0].getRowStride(),
                    planes[0].getPixelStride(),
                    planes[1].getRowStride(),
                    planes[1].getPixelStride(),
                    y,
                    u,
                    v
            );
        }
    }

    public static final class DetectionResult {
        public final int classId;
        public final String className;
        public final String coarseType;
        public final float confidence;
        public final float centerX;
        public final float centerY;
        public final float width;
        public final float height;

        public DetectionResult(
                int classId,
                String className,
                String coarseType,
                float confidence,
                float centerX,
                float centerY,
                float width,
                float height
        ) {
            this.classId = classId;
            this.className = className;
            this.coarseType = coarseType;
            this.confidence = confidence;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
        }
    }

}
