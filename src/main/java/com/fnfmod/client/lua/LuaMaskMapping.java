package com.fnfmod.client.lua;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Maps normalized target pixels into an independently transformed mask object. */
public record LuaMaskMapping(float xx, float xy, float yx, float yy, float tx, float ty) {
    public static LuaMaskMapping between(Matrix4f targetTransform, double targetWidth, double targetHeight,
                                         Matrix4f maskTransform, double maskWidth, double maskHeight,
                                         double offsetX, double offsetY,
                                         double scaleX, double scaleY, double angleDegrees) {
        if (targetTransform == null || maskTransform == null
                || !finitePositive(targetWidth) || !finitePositive(targetHeight)
                || !finitePositive(maskWidth) || !finitePositive(maskHeight)
                || !Double.isFinite(offsetX) || !Double.isFinite(offsetY)
                || !Double.isFinite(scaleX) || !Double.isFinite(scaleY)
                || !Double.isFinite(angleDegrees)
                || Math.abs(scaleX) < 0.00001 || Math.abs(scaleY) < 0.00001
                || Math.abs(maskTransform.determinant()) < 0.000001) return null;

        Matrix4f targetToMask = new Matrix4f(maskTransform).invert().mul(targetTransform);
        Vector3f origin = sample(targetToMask, 0, 0, targetWidth, targetHeight,
                maskWidth, maskHeight, offsetX, offsetY, scaleX, scaleY, angleDegrees);
        Vector3f right = sample(targetToMask, 1, 0, targetWidth, targetHeight,
                maskWidth, maskHeight, offsetX, offsetY, scaleX, scaleY, angleDegrees);
        Vector3f down = sample(targetToMask, 0, 1, targetWidth, targetHeight,
                maskWidth, maskHeight, offsetX, offsetY, scaleX, scaleY, angleDegrees);
        if (!finite(origin) || !finite(right) || !finite(down)) return null;
        return new LuaMaskMapping(
                right.x - origin.x, down.x - origin.x,
                right.y - origin.y, down.y - origin.y,
                origin.x, origin.y);
    }

    private static Vector3f sample(Matrix4f targetToMask, double u, double v,
                                   double targetWidth, double targetHeight,
                                   double maskWidth, double maskHeight,
                                   double offsetX, double offsetY,
                                   double scaleX, double scaleY, double angleDegrees) {
        Vector3f point = targetToMask.transformPosition(new Vector3f(
                (float) ((u - 0.5) * targetWidth),
                (float) ((v - 0.5) * targetHeight), 0));
        double x = point.x / maskWidth + 0.5 - offsetX - 0.5;
        double y = point.y / maskHeight + 0.5 - offsetY - 0.5;
        double angle = Math.toRadians(-angleDegrees);
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double rotatedX = cos * x - sin * y;
        double rotatedY = sin * x + cos * y;
        return new Vector3f((float) (rotatedX / scaleX + 0.5),
                (float) (rotatedY / scaleY + 0.5), 0);
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static boolean finite(Vector3f value) {
        return Float.isFinite(value.x) && Float.isFinite(value.y);
    }
}
