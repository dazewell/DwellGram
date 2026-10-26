package com.dazewell.gram.videonote;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import org.telegram.messenger.FileLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * #camera-memo: the whole camera frame, uncropped, for the viewfinder behind a camera memo's circle. The round preview
 * only crops by texture coordinates (InstantCameraView's texData), so this draws the same camera texture with the full
 * 0..1 range into the cover's own TextureView, from InstantCameraView's CameraGLThread right after each preview frame.
 *
 * It runs on the recording's GL thread, so it is built to never touch the recording: it only reads the camera texture
 * and matrices, never writes their state, puts back the current surface and viewport, never blocks on a swap, and any
 * EGL error switches it off for that GL context, leaving the viewfinder dark and the recording as it was.
 *
 * Ownership of the target SurfaceTexture: the cover's TextureView hands it over (setTarget) and takes it back
 * (clearTarget, its onSurfaceTextureDestroyed returning false). Whoever holds it last releases it: the UI thread when no
 * EGL surface was made on it, the GL thread once it has destroyed the one it made.
 */
public final class CameraMemoBackdrop {

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "   gl_Position = uMVPMatrix * aPosition;\n" +
            "   vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision lowp float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "   gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    private static final Object LOCK = new Object();

    // Handed over by the UI thread, under LOCK
    private static volatile SurfaceTexture target;
    private static volatile int targetWidth;
    private static volatile int targetHeight;
    // Under LOCK. claimed: the target the GL thread is making, or has made, an EGL surface on. abandoned: a claimed
    // target the UI no longer wants, for the GL thread to release once its surface is gone.
    private static SurfaceTexture claimed;
    private static SurfaceTexture abandoned;
    // The one GL context drawing the viewfinder. Every other round video, in any chat, returns at the first line.
    private static volatile EGLContext owner;

    // The upright full frame against the circle's crop: its width and height in crop sides. Written by the GL thread,
    // read by the cover to size the viewfinder. 0 until the first frame.
    static volatile float frameWidth;
    static volatile float frameHeight;

    // GL thread only, and only while owner is its context
    private static EGLSurface windowSurface;
    private static SurfaceTexture windowTarget;
    private static boolean swapIntervalSet;
    private static EGLContext programContext;
    private static int program;
    private static int positionHandle;
    private static int textureHandle;
    private static int vertexMatrixHandle;
    private static int textureMatrixHandle;
    // The kill switch, scoped to the target it failed on: a later camera memo, with a new TextureView, tries again
    private static boolean disabled;
    private static SurfaceTexture disabledFor;
    private static FloatBuffer vertexBuffer;
    private static FloatBuffer fullTextureBuffer;
    private static final int[] viewport = new int[4];

    private CameraMemoBackdrop() {
    }

    // --- UI thread: the cover's TextureView ---

    static void setTarget(SurfaceTexture surface, int width, int height) {
        synchronized (LOCK) {
            targetWidth = width;
            targetHeight = height;
            target = surface;
        }
    }

    static void clearTarget(SurfaceTexture surface) {
        synchronized (LOCK) {
            if (target == surface) {
                target = null;
            }
            if (claimed == surface) {
                abandoned = surface;
            } else {
                surface.release();
            }
        }
    }

    // --- CameraGLThread ---

    /**
     * After every preview frame's swap, with the camera surface current. cropTexCoords is the preview's own texture
     * buffer: its first pair is (0.5 - tX, 0.5 - tY), which says how much of the frame the circle shows.
     */
    public static void onFrame(EGL10 egl, EGLDisplay display, EGLContext context, EGLSurface cameraSurface, int cameraTexture,
                               float[] stMatrix, float[] mvpMatrix, FloatBuffer cropTexCoords) {
        if (target == null && owner != context) {
            return;
        }
        EGLContext current = owner;
        if (current != null && current != context) {
            return;
        }
        try {
            publishFrameShape(cropTexCoords, mvpMatrix);
            SurfaceTexture want = target;
            if (disabled && want != disabledFor) {
                disabled = false;
                disabledFor = null;
            }
            if (windowTarget != null && windowTarget != want) {
                dropWindowSurface(egl, display);
            }
            if (want == null) {
                synchronized (LOCK) {
                    if (target == null && claimed == null) {
                        owner = null;
                    }
                }
                return;
            }
            if (disabled) {
                return;
            }
            if (windowTarget == null) {
                synchronized (LOCK) {
                    if (target != want || owner != null && owner != context) {
                        return; // gone already, or another GL thread got there first
                    }
                    claimed = want; // before any EGL call on it, so the UI never releases it under the surface
                    owner = context;
                }
                windowSurface = createWindowSurface(egl, display, context, want);
                if (windowSurface == null) {
                    fail();
                    unclaim(want);
                    return;
                }
                windowTarget = want;
                swapIntervalSet = false;
            }
            draw(egl, display, context, cameraSurface, cameraTexture, stMatrix, mvpMatrix);
        } catch (Throwable e) {
            FileLog.e(e);
            fail();
            try {
                dropWindowSurface(egl, display);
            } catch (Throwable ignore) {
            }
        }
    }

    /** CameraGLThread.finish, while its context is still current. The context is destroyed right after. */
    public static void onGlShutdown(EGL10 egl, EGLDisplay display, EGLContext context) {
        if (owner != context) {
            return;
        }
        try {
            dropWindowSurface(egl, display);
            if (program != 0 && programContext == context) {
                GLES20.glDeleteProgram(program);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        program = 0;
        programContext = null;
        disabled = false;
        disabledFor = null;
        synchronized (LOCK) {
            // A target still wanted is left alone: the next GL thread (a resume) claims it again
            claimed = null;
            owner = null;
        }
    }

    private static void fail() {
        disabled = true;
        disabledFor = windowTarget != null ? windowTarget : target;
    }

    private static void dropWindowSurface(EGL10 egl, EGLDisplay display) {
        SurfaceTexture was = windowTarget;
        if (windowSurface != null) {
            egl.eglDestroySurface(display, windowSurface); // never the current surface: draw always switches back
            windowSurface = null;
        }
        windowTarget = null;
        if (was != null) {
            unclaim(was);
        }
    }

    private static void unclaim(SurfaceTexture surface) {
        synchronized (LOCK) {
            if (claimed == surface) {
                claimed = null;
            }
            if (abandoned == surface) {
                abandoned = null;
                surface.release();
            }
        }
    }

    // The crop is applied to the texture coordinates before the SurfaceTexture matrix, so that matrix only picks which
    // texels are sampled, never how far the s and t ranges reach on screen. Only the quad's own turn (the world angle in
    // the vertex matrix) swaps which of them lies along the screen's width.
    private static void publishFrameShape(FloatBuffer crop, float[] mvpMatrix) {
        if (crop == null || crop.capacity() < 2) {
            return;
        }
        float fx = 1f - 2f * crop.get(0); // the share of the frame's u range the circle shows
        float fy = 1f - 2f * crop.get(1);
        if (!(fx > 0f && fx <= 1f && fy > 0f && fy <= 1f)) {
            return;
        }
        boolean turned = Math.abs(mvpMatrix[0]) < 0.5f;
        float width = 1f / (turned ? fy : fx);
        float height = 1f / (turned ? fx : fy);
        if (frameWidth != width || frameHeight != height) {
            frameWidth = width;
            frameHeight = height;
        }
    }

    private static EGLSurface createWindowSurface(EGL10 egl, EGLDisplay display, EGLContext context, SurfaceTexture surface) {
        // initGL keeps its config in a local, so find it again from the context
        int[] value = new int[1];
        if (!egl.eglQueryContext(display, context, EGL10.EGL_CONFIG_ID, value)) {
            return null;
        }
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!egl.eglChooseConfig(display, new int[]{EGL10.EGL_CONFIG_ID, value[0], EGL10.EGL_NONE}, configs, 1, count) || count[0] < 1) {
            return null;
        }
        // eglSwapInterval clamps to this, and a swap that waits on an undrawn viewfinder would hold the camera thread
        if (!egl.eglGetConfigAttrib(display, configs[0], EGL14.EGL_MIN_SWAP_INTERVAL, value) || value[0] > 0) {
            return null;
        }
        EGLSurface created = egl.eglCreateWindowSurface(display, configs[0], surface, null);
        return created == null || created == EGL10.EGL_NO_SURFACE ? null : created;
    }

    private static void draw(EGL10 egl, EGLDisplay display, EGLContext context, EGLSurface cameraSurface, int cameraTexture,
                             float[] stMatrix, float[] mvpMatrix) {
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0); // nothing on the GL thread sets it: the preview relies on the first one
        boolean switched = false;
        try {
            if (!egl.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
                fail();
                return;
            }
            switched = true;
            if (!swapIntervalSet) {
                // A viewfinder nobody draws (the cover hidden or going away) must never hold the camera thread on a swap
                if (!EGL14.eglSwapInterval(EGL14.eglGetCurrentDisplay(), 0)) {
                    fail();
                    return;
                }
                swapIntervalSet = true;
            }
            if (!ensureProgram(context)) {
                fail();
                return;
            }
            int width = targetWidth;
            int height = targetHeight;
            if (width <= 0 || height <= 0) {
                return;
            }
            GLES20.glViewport(0, 0, width, height);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture);
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, fullTextureBuffer);
            GLES20.glEnableVertexAttribArray(textureHandle);
            GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, stMatrix, 0);
            GLES20.glUniformMatrix4fv(vertexMatrixHandle, 1, false, mvpMatrix, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(textureHandle);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glUseProgram(0);
            if (!egl.eglSwapBuffers(display, windowSurface)) {
                fail();
            }
        } finally {
            if (switched) {
                // If this fails too, the preview's own check makes its surface current again on the next frame
                egl.eglMakeCurrent(display, cameraSurface, cameraSurface, context);
            }
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (disabled) {
                dropWindowSurface(egl, display);
            }
        }
    }

    private static boolean ensureProgram(EGLContext context) {
        if (program != 0 && programContext == context) {
            return true;
        }
        program = 0;
        int vertex = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (vertex == 0 || fragment == 0) {
            return false;
        }
        int created = GLES20.glCreateProgram();
        GLES20.glAttachShader(created, vertex);
        GLES20.glAttachShader(created, fragment);
        GLES20.glLinkProgram(created);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        int[] status = new int[1];
        GLES20.glGetProgramiv(created, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            GLES20.glDeleteProgram(created);
            return false;
        }
        positionHandle = GLES20.glGetAttribLocation(created, "aPosition");
        textureHandle = GLES20.glGetAttribLocation(created, "aTextureCoord");
        vertexMatrixHandle = GLES20.glGetUniformLocation(created, "uMVPMatrix");
        textureMatrixHandle = GLES20.glGetUniformLocation(created, "uSTMatrix");
        if (vertexBuffer == null) {
            vertexBuffer = buffer(new float[]{-1f, -1f, 0, 1f, -1f, 0, -1f, 1f, 0, 1f, 1f, 0});
            fullTextureBuffer = buffer(new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f});
        }
        program = created;
        programContext = context;
        return true;
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static FloatBuffer buffer(float[] data) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(data).position(0);
        return buffer;
    }
}
