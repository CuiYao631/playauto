package cn.autoplay.replacement;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Original software path: packed I420 planes, GLES2 luminance textures, limited-range BT.601. */
final class OpenH264GlView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private final H264SurfaceRenderer.Listener listener;
    private final FloatBuffer vertices = floats(-1, -1, 1, -1, -1, 1, 1, 1);
    private final FloatBuffer coordinates = floats(0, 1, 1, 1, 0, 0, 1, 0);
    private final int[] textures = new int[3];
    private final ByteBuffer[] planes = new ByteBuffer[3];
    private int width;
    private int height;
    private int program;
    private boolean failed;

    OpenH264GlView(Context context, H264SurfaceRenderer.Listener listener) {
        super(context);
        this.listener = listener;
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    synchronized void submit(byte[] i420, int size, int w, int h) {
        if (w <= 0 || h <= 0 || w > 4000 || h > 4000 || (w & 1) != 0 || (h & 1) != 0
                || size != w * h * 3 / 2 || size > i420.length) {
            throw new IllegalArgumentException("Invalid OpenH264 I420 output: " + w + "x" + h + "/" + size);
        }
        if (width != w || height != h) {
            planes[0] = ByteBuffer.allocateDirect(w * h);
            planes[1] = ByteBuffer.allocateDirect(w * h / 4);
            planes[2] = ByteBuffer.allocateDirect(w * h / 4);
            width = w;
            height = h;
        }
        int offset = 0;
        for (ByteBuffer plane : planes) {
            plane.clear();
            plane.put(i420, offset, plane.capacity()).flip();
            offset += plane.capacity();
        }
        requestRender();
    }

    @Override public synchronized void onSurfaceCreated(GL10 gl, EGLConfig config) {
        failed = false;
        try {
            int vertex = shader(GLES20.GL_VERTEX_SHADER,
                    "attribute vec4 position;attribute vec2 uv;varying vec2 tc;"
                            + "void main(){gl_Position=position;tc=uv;}");
            int fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float;varying vec2 tc;"
                            + "uniform sampler2D y;uniform sampler2D u;uniform sampler2D v;"
                            + "void main(){float Y=(texture2D(y,tc).r-16./255.)*1.164;"
                            + "float U=texture2D(u,tc).r-128./255.;float V=texture2D(v,tc).r-128./255.;"
                            + "gl_FragColor=vec4(Y+1.596*V,Y-.813*V-.392*U,Y+2.017*U,1.);}");
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            GLES20.glGenTextures(3, textures, 0);
            for (int texture : textures) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            }
        } catch (RuntimeException error) {
            report(error);
        }
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
    }

    @Override public synchronized void onDrawFrame(GL10 gl) {
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (failed || width == 0) return;
        try {
            GLES20.glUseProgram(program);
            int position = GLES20.glGetAttribLocation(program, "position");
            int uv = GLES20.glGetAttribLocation(program, "uv");
            vertices.position(0);
            coordinates.position(0);
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glEnableVertexAttribArray(uv);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, coordinates);
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
            String[] names = {"y", "u", "v"};
            for (int i = 0; i < 3; i++) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
                planes[i].position(0);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                        i == 0 ? width : width / 2, i == 0 ? height : height / 2,
                        0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, planes[i]);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, names[i]), i);
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException("GLES2 error " + error);
        } catch (RuntimeException error) {
            report(error);
        }
    }

    private void report(RuntimeException error) {
        failed = true;
        android.util.Log.e("AutoPlayVideo", "OpenH264 GL rendering failed", error);
        if (listener != null) post(() -> listener.onDecoderError("OpenH264 OpenGL：" + error.getMessage()));
    }

    private static int shader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException(error);
        }
        return shader;
    }

    private static FloatBuffer floats(float... values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }
}
