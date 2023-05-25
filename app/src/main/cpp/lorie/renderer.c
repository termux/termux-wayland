#pragma clang diagnostic ignored "-Wunknown-pragmas"
#pragma ide diagnostic ignored "UnusedParameter"
#pragma ide diagnostic ignored "DanglingPointer"
#pragma ide diagnostic ignored "ConstantConditionsOC"
#pragma ide diagnostic ignored "OCUnusedGlobalDeclarationInspection"
#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GL/gl.h>
#include <android/native_window.h>
#include <android/log.h>
#include <dlfcn.h>
#include "renderer.h"
#include "os.h"

// We can not link both mesa's GL and Android's GLES without interfering.
// That is the only way to do this without creating linker namespaces.

#define eglFunctions(a, m)             \
m(a, eglGetDisplay)                    \
m(a, eglGetError)                      \
m(a, eglInitialize)                    \
m(a, eglChooseConfig)                  \
m(a, eglBindAPI)                       \
m(a, eglCreateContext)                 \
m(a, eglMakeCurrent)                   \
m(a, eglSwapInterval)                  \
m(a, eglDestroySurface)                \
m(a, eglGetNativeClientBufferANDROID)  \
m(a, eglCreateImageKHR)                \
m(a, eglDestroyImageKHR)               \
m(a, eglCreateWindowSurface)           \
m(a, eglSwapBuffers)

#define glFunctions(a, m)              \
m(a, glGetError)                       \
m(a, glCreateShader)                   \
m(a, glShaderSource)                   \
m(a, glCompileShader)                  \
m(a, glGetShaderiv)                    \
m(a, glGetShaderInfoLog)               \
m(a, glDeleteShader)                   \
m(a, glCreateProgram)                  \
m(a, glAttachShader)                   \
m(a, glLinkProgram)                    \
m(a, glGetProgramiv)                   \
m(a, glGetProgramInfoLog)              \
m(a, glDeleteProgram)                  \
m(a, glActiveTexture)                  \
m(a, glUseProgram)                     \
m(a, glBindTexture)                    \
m(a, glVertexAttribPointer)            \
m(a, glEnableVertexAttribArray)        \
m(a, glDrawArrays)                     \
m(a, glEnable)                         \
m(a, glBlendFunc)                      \
m(a, glDisable)                        \
m(a, glGetAttribLocation)              \
m(a, glGenTextures)                    \
m(a, glViewport)                       \
m(a, glClearColor)                     \
m(a, glClear)                          \
m(a, glTexParameteri)                  \
m(a, glTexImage2D)                     \
m(a, glTexSubImage2D)                  \
m(a, glEGLImageTargetTexture2DOES)

#define defineFuncPointer(a, name) static __typeof__(name)* $##name = NULL;
#define SYMBOL(lib, name) $ ## name = dlsym(lib, #name);

eglFunctions(0, defineFuncPointer)
glFunctions(0, defineFuncPointer)

static void init(void) {
    void *libEGL, *libGLESv2;
    if ($eglGetDisplay)
        return;
    libEGL = dlopen("libEGL.so", RTLD_NOW);
    libGLESv2 = dlopen("libGLESv2.so", RTLD_NOW);

    eglFunctions(libEGL, SYMBOL)
    glFunctions(libGLESv2, SYMBOL)
}

//#define log(...) logMessage(X_ERROR, -1, __VA_ARGS__)
#define log(...) __android_log_print(ANDROID_LOG_DEBUG, "gles-renderer", __VA_ARGS__)

static GLuint create_program(const char* p_vertex_source, const char* p_fragment_source);

static void eglCheckError(int line) {
    char* desc;
    switch($eglGetError()) {
#define E(code, text) case code: desc = (char*) text; break
        case EGL_SUCCESS: desc = NULL; // "No error"
        E(EGL_NOT_INITIALIZED, "EGL not initialized or failed to initialize");
        E(EGL_BAD_ACCESS, "Resource inaccessible");
        E(EGL_BAD_ALLOC, "Cannot allocate resources");
        E(EGL_BAD_ATTRIBUTE, "Unrecognized attribute or attribute value");
        E(EGL_BAD_CONTEXT, "Invalid EGL context");
        E(EGL_BAD_CONFIG, "Invalid EGL frame buffer configuration");
        E(EGL_BAD_CURRENT_SURFACE, "Current surface is no longer valid");
        E(EGL_BAD_DISPLAY, "Invalid EGL display");
        E(EGL_BAD_SURFACE, "Invalid surface");
        E(EGL_BAD_MATCH, "Inconsistent arguments");
        E(EGL_BAD_PARAMETER, "Invalid argument");
        E(EGL_BAD_NATIVE_PIXMAP, "Invalid native pixmap");
        E(EGL_BAD_NATIVE_WINDOW, "Invalid native window");
        E(EGL_CONTEXT_LOST, "Context lost");
#undef E
        default: desc = (char*) "Unknown error";
    }

    if (desc)
        log("Xlorie: egl error on line %d: %s\n", line, desc);
}

static void checkGlError(int line) {
    GLenum error;
    char *desc = NULL;
    for (error = $glGetError(); error; error = $glGetError()) {
        switch (error) {
#define E(code) case code: desc = (char*)#code; break
            E(GL_INVALID_ENUM);
            E(GL_INVALID_VALUE);
            E(GL_INVALID_OPERATION);
            E(GL_STACK_OVERFLOW_KHR);
            E(GL_STACK_UNDERFLOW_KHR);
            E(GL_OUT_OF_MEMORY);
            E(GL_INVALID_FRAMEBUFFER_OPERATION);
            E(GL_CONTEXT_LOST_KHR);
            default:
                continue;
#undef E
        }
        log("Xlorie: GLES %d ERROR: %s.\n", line, desc);
        return;
    }
}

#define checkGlError() checkGlError(__LINE__)


static const char vertex_shader[] =
    "attribute vec4 position;\n"
    "attribute vec2 texCoords;"
    "varying vec2 outTexCoords;\n"
    "void main(void) {\n"
    "   outTexCoords = texCoords;\n"
    "   gl_Position = position;\n"
    "}\n";
static const char fragment_shader[] =
    "precision mediump float;\n"
    "varying vec2 outTexCoords;\n"
    "uniform sampler2D texture;\n"
    "void main(void) {\n"
    "   gl_FragColor = texture2D(texture, outTexCoords);\n"
    "}\n";

static EGLDisplay egl_display = EGL_NO_DISPLAY;
static EGLContext ctx = EGL_NO_CONTEXT;
static EGLSurface sfc = EGL_NO_SURFACE;
static EGLConfig cfg = 0;
static EGLNativeWindowType win = 0;
static EGLImageKHR image = NULL;

static struct {
    GLuint id;
    float width, height;
} display;
static struct {
    GLuint id;
    float x, y, width, height, xhot, yhot;
} cursor;

GLuint g_texture_program = 0, gv_pos = 0, gv_coords = 0;

int renderer_init(void) {
    EGLint major, minor;
    EGLint numConfigs;
    const EGLint configAttribs[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE
    };
    const EGLint ctxattribs[] = {
            EGL_CONTEXT_CLIENT_VERSION,2, EGL_NONE
    };

    if (ctx)
        return 1;
    init();

    egl_display = $eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglCheckError(__LINE__);
    if (egl_display == EGL_NO_DISPLAY) {
        log("Xlorie: Got no EGL display.\n");
        return 0;
    }

    if ($eglInitialize(egl_display, &major, &minor) != EGL_TRUE) {
        log("Xlorie: Unable to initialize EGL\n");
        return 0;
    }
    log("Xlorie: Initialized EGL version %d.%d\n", major, minor);
    eglCheckError(__LINE__);

    if ($eglChooseConfig(egl_display, configAttribs, &cfg, 1, &numConfigs) != EGL_TRUE) {
        log("Xlorie: eglChooseConfig failed.\n");
        eglCheckError(__LINE__);
        return 0;
    }

    $eglBindAPI(EGL_OPENGL_ES_API);
    eglCheckError(__LINE__);

    ctx = $eglCreateContext(egl_display, cfg, NULL, ctxattribs);
    eglCheckError(__LINE__);
    if (ctx == EGL_NO_CONTEXT) {
        log("Xlorie: eglCreateContext failed.\n");
        eglCheckError(__LINE__);
        return 0;
    }

    if ($eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx) != EGL_TRUE) {
        log("Xlorie: eglMakeCurrent failed.\n");
        eglCheckError(__LINE__);
        return 0;
    }
    eglCheckError(__LINE__);

    g_texture_program = create_program(vertex_shader, fragment_shader);
    if (!g_texture_program) {
        log("Xlorie: GLESv2: Unable to create shader program.\n");
        eglCheckError(__LINE__);
        return 1;
    }

    gv_pos = (GLuint) $glGetAttribLocation(g_texture_program, "position"); checkGlError();
    gv_coords = (GLuint) $glGetAttribLocation(g_texture_program, "texCoords"); checkGlError();

    $glActiveTexture(GL_TEXTURE0); checkGlError();
    $glGenTextures(1, &display.id); checkGlError();
    $glGenTextures(1, &cursor.id); checkGlError();

    return 1;
}

void renderer_set_buffer(AHardwareBuffer* buffer) {
    const EGLint imageAttributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLClientBuffer clientBuffer;
    AHardwareBuffer_Desc desc;
    __android_log_print(ANDROID_LOG_DEBUG, "XlorieTest2", "renderer_set_buffer0");
    if (image)
        $eglDestroyImageKHR(egl_display, image);

    AHardwareBuffer_describe(buffer, &desc);

    display.width = (float) desc.width;
    display.height = (float) desc.height;

    clientBuffer = $eglGetNativeClientBufferANDROID(buffer); eglCheckError(__LINE__);
    image = $eglCreateImageKHR(egl_display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, imageAttributes); eglCheckError(__LINE__);

    $glBindTexture(GL_TEXTURE_2D, display.id); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE); checkGlError();
    $glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image); checkGlError();
    renderer_redraw();

    __android_log_print(ANDROID_LOG_DEBUG, "XlorieTest2", "renderer_set_buffer %d %d", desc.width, desc.height);
}

void renderer_set_window(EGLNativeWindowType window) {
    __android_log_print(ANDROID_LOG_DEBUG, "XlorieTest2", "renderer_set_window %p %d %d", window, win ? ANativeWindow_getWidth(win) : 0, win ? ANativeWindow_getHeight(win) : 0);
    if (win == window)
        return;

    if (sfc != EGL_NO_SURFACE) {
        if ($eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) != EGL_TRUE) {
            log("Xlorie: eglMakeCurrent (EGL_NO_SURFACE) failed.\n");
            eglCheckError(__LINE__);
            return;
        }
        if ($eglDestroySurface(egl_display, sfc) != EGL_TRUE) {
            log("Xlorie: eglDestoySurface failed.\n");
            eglCheckError(__LINE__);
            return;
        }
    }

    if (win)
        ANativeWindow_release(win);
    win = window;

    sfc = $eglCreateWindowSurface(egl_display, cfg, win, NULL);
    if (sfc == EGL_NO_SURFACE) {
        log("Xlorie: eglCreateWindowSurface failed.\n");
        eglCheckError(__LINE__);
        return;
    }

    if ($eglMakeCurrent(egl_display, sfc, sfc, ctx) != EGL_TRUE) {
        log("Xlorie: eglMakeCurrent failed.\n");
        eglCheckError(__LINE__);
        return;
    }

    $eglSwapInterval(egl_display, 0);

    if (win && ctx && ANativeWindow_getWidth(win) && ANativeWindow_getHeight(win))
        $glViewport(0, 0, ANativeWindow_getWidth(win), ANativeWindow_getHeight(win)); checkGlError();

    log("Xlorie: new surface applied: %p\n", sfc);

    if (!sfc)
        return;

    $glClearColor(1.f, 0.f, 0.f, 0.0f); checkGlError();
    $glClear(GL_COLOR_BUFFER_BIT); checkGlError();
    renderer_redraw();
}

maybe_unused void renderer_upload(int w, int h, void* data) {
    display.width = (float) w;
    display.height = (float) h;
    $glBindTexture(GL_TEXTURE_2D, display.id); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE); checkGlError();

    $glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, data); checkGlError();
}

maybe_unused void renderer_update_rects(int width, maybe_unused int height, pixman_box16_t *rects, int amount, void* data) {
    int i, w, j;
    uint32_t* d;
    display.width = (float) width;
    display.height = (float) height;
    $glBindTexture(GL_TEXTURE_2D, display.id); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE); checkGlError();

    for (i = 0; i < amount; i++)
        for (j = rects[i].y1; j < rects[i].y2; j++) {
            d = &((uint32_t*)data)[width * j + rects[i].x1];
            w = rects[i].x2 - rects[i].x1;
            $glTexSubImage2D(GL_TEXTURE_2D, 0, rects[i].x1, j, w, 1, GL_RGBA, GL_UNSIGNED_BYTE, d); checkGlError();
        }
}

void renderer_update_cursor(int w, int h, int xhot, int yhot, void* data) {
    log("Xlorie: updating cursor\n");
    cursor.width = (float) w;
    cursor.height = (float) h;
    cursor.xhot = (float) xhot;
    cursor.yhot = (float) yhot;

    $glBindTexture(GL_TEXTURE_2D, cursor.id); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); checkGlError();
    $glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE); checkGlError();

    $glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, data); checkGlError();
}

void renderer_set_cursor_coordinates(int x, int y) {
    cursor.x = (float) x;
    cursor.y = (float) y;
}

static void draw(GLuint id, float x0, float y0, float x1, float y1);
static void draw_cursor(void);

float ia = 0;

void renderer_redraw(void) {
    if (!sfc)
        return;

    draw(display.id,  -1.f, -1.f, 1.f, 1.f);
    draw_cursor();
    $eglSwapBuffers(egl_display, sfc); checkGlError();
}

static GLuint load_shader(GLenum shaderType, const char* pSource) {
    GLint compiled = 0;
    GLuint shader = $glCreateShader(shaderType); checkGlError();
    if (shader) {
        $glShaderSource(shader, 1, &pSource, NULL); checkGlError();
        $glCompileShader(shader); checkGlError();
        $glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled); checkGlError();
        if (!compiled) {
            GLint infoLen = 0;
            $glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen); checkGlError();
            if (infoLen) {
                char* buf = (char*) malloc(infoLen);
                if (buf) {
                    $glGetShaderInfoLog(shader, infoLen, NULL, buf); checkGlError();
                    log("Xlorie: Could not compile shader %d:\n%s\n", shaderType, buf);
                    free(buf);
                }
                $glDeleteShader(shader); checkGlError();
                shader = 0;
            }
        }
    }
    return shader;
}

static GLuint create_program(const char* p_vertex_source, const char* p_fragment_source) {
    GLuint program, vertexShader, pixelShader;
    GLint linkStatus = GL_FALSE;
    vertexShader = load_shader(GL_VERTEX_SHADER, p_vertex_source);
    pixelShader = load_shader(GL_FRAGMENT_SHADER, p_fragment_source);
    if (!pixelShader || !vertexShader) {
        return 0;
    }

    program = $glCreateProgram(); checkGlError();
    if (program) {
        $glAttachShader(program, vertexShader); checkGlError();
        $glAttachShader(program, pixelShader); checkGlError();
        $glLinkProgram(program); checkGlError();
        $glGetProgramiv(program, GL_LINK_STATUS, &linkStatus); checkGlError();
        if (linkStatus != GL_TRUE) {
            GLint bufLength = 0;
            $glGetProgramiv(program, GL_INFO_LOG_LENGTH, &bufLength); checkGlError();
            if (bufLength) {
                char* buf = (char*) malloc(bufLength);
                if (buf) {
                    $glGetProgramInfoLog(program, bufLength, NULL, buf); checkGlError();
                    log("Xlorie: Could not link program:\n%s\n", buf);
                    free(buf);
                }
            }
            $glDeleteProgram(program); checkGlError();
            program = 0;
        }
    }
    return program;
}

static void draw(GLuint id, float x0, float y0, float x1, float y1) {
    float coords[20] = {
        x0, -y0, 0.f, 0.f, 0.f,
        x1, -y0, 0.f, 1.f, 0.f,
        x0, -y1, 0.f, 0.f, 1.f,
        x1, -y1, 0.f, 1.f, 1.f,
    };

    $glActiveTexture(GL_TEXTURE0); checkGlError();
    $glUseProgram(g_texture_program); checkGlError();
    $glBindTexture(GL_TEXTURE_2D, id); checkGlError();

    $glVertexAttribPointer(gv_pos, 3, GL_FLOAT, GL_FALSE, 20, coords); checkGlError();
    $glVertexAttribPointer(gv_coords, 2, GL_FLOAT, GL_FALSE, 20, &coords[3]); checkGlError();
    $glEnableVertexAttribArray(gv_pos); checkGlError();
    $glEnableVertexAttribArray(gv_coords); checkGlError();
    $glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); checkGlError();
}

maybe_unused static void draw_cursor(void) {
    float x, y, w, h;
    x = 2.f * (cursor.x - cursor.xhot) / display.width - 1.f;
    y = 2.f * (cursor.y - cursor.yhot) / display.height - 1.f;
    w = 2.f * cursor.width / display.width;
    h = 2.f * cursor.height / display.height;
    $glEnable(GL_BLEND); checkGlError();
    $glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); checkGlError();
    draw(cursor.id, x, y, x + w, y + h);
    $glDisable(GL_BLEND); checkGlError();
}

