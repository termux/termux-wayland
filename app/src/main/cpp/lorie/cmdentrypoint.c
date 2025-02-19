#pragma clang diagnostic ignored "-Wunknown-pragmas"
#pragma clang diagnostic ignored "-Wmissing-prototypes"
#pragma ide diagnostic ignored "bugprone-reserved-identifier"
#pragma ide diagnostic ignored "OCUnusedMacroInspection"
#pragma ide diagnostic ignored "EndlessLoop"
#define __USE_GNU
#ifdef HAVE_DIX_CONFIG_H
#include <dix-config.h>
#endif
#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <libgen.h>
#include <globals.h>
#include <xkbsrv.h>
#include <errno.h>
#include <inpututils.h>
#include <randrstr.h>
#include <linux/in.h>
#include <arpa/inet.h>
#include <poll.h>
#include "lorie.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_ ## prio, "LorieNative", __VA_ARGS__)

static int argc = 0;
static char** argv = NULL;
__LIBC_HIDDEN__ volatile int conn_fd = -1; // The only variable shared with activity code.
extern DeviceIntPtr lorieMouse, lorieTouch, lorieKeyboard, loriePen, lorieEraser;
extern ScreenPtr pScreenPtr;
extern int ucs2keysym(long ucs);
void lorieKeysymKeyboardEvent(KeySym keysym, int down);

char *xtrans_unix_path_x11 = NULL;
char *xtrans_unix_dir_x11 = NULL;

struct xorg_list registeredBuffers;

static void* startServer(__unused void* cookie) {
    char* envp[] = { NULL };
    exit(dix_main(argc, (char**) argv, envp));
}

static Bool detectTracer(void)
{
    FILE *fp;
    char  line[256];
    int pid = 0;

    fp = fopen("/proc/self/status", "r");
    if (!fp)
        return TRUE;

    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            sscanf(line+10, "%d", &pid);
            break;
        }
    }

    if (pid != 0)
        log(INFO, "Tracer detected");

    fclose(fp);
    return pid != 0;
}

JNIEXPORT jboolean JNICALL
Java_com_termux_x11_CmdEntryPoint_start(JNIEnv *env, __unused jclass cls, jobjectArray args) {
    pthread_t t;
    JavaVM* vm = NULL;
    // execv's argv array is a bit incompatible with Java's String[], so we do some converting here...
    argc = (*env)->GetArrayLength(env, args) + 1; // Leading executable path
    argv = (char**) calloc(argc, sizeof(char*));

    argv[0] = (char*) "Xlorie";
    for(int i=1; i<argc; i++) {
        jstring js = (jstring)((*env)->GetObjectArrayElement(env, args, i - 1));
        const char *pjc = (*env)->GetStringUTFChars(env, js, JNI_FALSE);
        argv[i] = (char *) calloc(strlen(pjc) + 1, sizeof(char)); //Extra char for the terminating NULL
        strcpy((char *) argv[i], pjc);
        (*env)->ReleaseStringUTFChars(env, js, pjc);
    }

    {
        cpu_set_t mask;
        long num_cpus = sysconf(_SC_NPROCESSORS_ONLN);

        for (int i = num_cpus/2; i < num_cpus; i++)
            CPU_SET(i, &mask);

        if (sched_setaffinity(0, sizeof(cpu_set_t), &mask) == -1)
            log(ERROR, "Failed to set process affinity: %s", strerror(errno));
    }

    if (getenv("TERMUX_X11_DEBUG") && !fork()) {
        // Printing logs of local logcat.
        char pid[32] = {0};
        prctl(PR_SET_PDEATHSIG, SIGTERM);
        sprintf(pid, "%d", getppid());
        execlp("logcat", "logcat", "--pid", pid, NULL);
    }

    // No matter what tracer is attached.
    // In the case of gdb or lldb LD_PRELOAD is already set.
    // In the case of proot or proot-distro libtermux-exec in LD_PRELOAD will break linking.
    if (access("/data/data/com.termux/files/usr/lib/libtermux-exec.so", F_OK) == 0 && !detectTracer()
            && !getenv("XSTARTUP_LD_PRELOAD"))
        setenv("LD_PRELOAD", "/data/data/com.termux/files/usr/lib/libtermux-exec.so", 1);

    // adb sets TMPDIR to /data/local/tmp which is pretty useless.
    if (!strcmp("/data/local/tmp", getenv("TMPDIR") ?: ""))
        unsetenv("TMPDIR");

    if (!getenv("TMPDIR")) {
        if (access("/tmp", F_OK) == 0)
            setenv("TMPDIR", "/tmp", 1);
        else if (access("/data/data/com.termux/files/usr/tmp", F_OK) == 0)
            setenv("TMPDIR", "/data/data/com.termux/files/usr/tmp", 1);
    }

    if (!getenv("TMPDIR")) {
        char* error = (char*) "$TMPDIR is not set. Normally it is pointing to /tmp of a container.";
        log(ERROR, "%s", error);
        dprintf(2, "%s\n", error);
        return JNI_FALSE;
    }

    {
        char* tmp = getenv("TMPDIR");
        char cwd[1024] = {0};

        if (!getcwd(cwd, sizeof(cwd)) || access(cwd, F_OK) != 0)
            chdir(tmp);
        asprintf(&xtrans_unix_path_x11, "%s/.X11-unix/X", tmp);
        asprintf(&xtrans_unix_dir_x11, "%s/.X11-unix/", tmp);
    }

    log(VERBOSE, "Using TMPDIR=\"%s\"", getenv("TMPDIR"));

    {
        const char *root_dir = dirname(getenv("TMPDIR"));
        const char* pathes[] = {
                "/etc/X11/fonts", "/usr/share/fonts/X11", "/share/fonts", NULL
        };
        for (int i=0; pathes[i]; i++) {
            char current_path[1024] = {0};
            snprintf(current_path, sizeof(current_path), "%s%s", root_dir, pathes[i]);
            if (access(current_path, F_OK) == 0) {
                char default_font_path[4096] = {0};
                snprintf(default_font_path, sizeof(default_font_path),
                         "%s/misc,%s/TTF,%s/OTF,%s/Type1,%s/100dpi,%s/75dpi",
                         current_path, current_path, current_path, current_path, current_path, current_path);
                defaultFontPath = strdup(default_font_path);
                break;
            }
        }
    }

    if (!getenv("XKB_CONFIG_ROOT")) {
        // chroot case
        const char *root_dir = dirname(getenv("TMPDIR"));
        char current_path[1024] = {0};
        snprintf(current_path, sizeof(current_path), "%s/usr/share/X11/xkb", root_dir);
        if (access(current_path, F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", current_path, 1);
    }

    if (!getenv("XKB_CONFIG_ROOT")) {
        // proot case
        if (access("/usr/share/X11/xkb", F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", "/usr/share/X11/xkb", 1);
        // Termux case
        else if (access("/data/data/com.termux/files/usr/share/X11/xkb", F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", "/data/data/com.termux/files/usr/share/X11/xkb", 1);
    }

    if (!getenv("XKB_CONFIG_ROOT")) {
        char* error = (char*) "$XKB_CONFIG_ROOT is not set. Normally it is pointing to /usr/share/X11/xkb of a container.";
        log(ERROR, "%s", error);
        dprintf(2, "%s\n", error);
        return JNI_FALSE;
    }

    XkbBaseDirectory = getenv("XKB_CONFIG_ROOT");
    if (access(XkbBaseDirectory, F_OK) != 0) {
        log(ERROR, "%s is unaccessible: %s\n", XkbBaseDirectory, strerror(errno));
        printf("%s is unaccessible: %s\n", XkbBaseDirectory, strerror(errno));
        return JNI_FALSE;
    }

    (*env)->GetJavaVM(env, &vm);

    AChoreographer *choreographer = AChoreographer_getInstance();
    // Trigger it first time
    AChoreographer_postFrameCallback(choreographer, (AChoreographer_frameCallback) lorieChoreographerFrameCallback, choreographer);

    xorg_list_init(&registeredBuffers);
    pthread_create(&t, NULL, startServer, vm);
    return JNI_TRUE;
}

static Bool sendConfigureNotify(__unused ClientPtr pClient, void *closure) {
    // This must be done only on X server thread.
    lorieEvent* e = closure;
    __android_log_print(ANDROID_LOG_ERROR, "tx11-request", "window changed: %d %d %s", e->screenSize.width, e->screenSize.height, e->screenSize.name);
    lorieConfigureNotify(e->screenSize.width, e->screenSize.height, e->screenSize.framerate, e->screenSize.name_size, e->screenSize.name);
    free(e);
    return TRUE;
}

static Bool handleClipboardAnnounce(__unused ClientPtr pClient, __unused void *closure) {
    // This must be done only on X server thread.
    lorieHandleClipboardAnnounce();
    return TRUE;
}

static Bool handleClipboardData(__unused ClientPtr pClient, void *closure) {
    // This must be done only on X server thread.
    lorieHandleClipboardData(closure);
    return TRUE;
}

static Bool handleTouchEvent(__unused ClientPtr pClient, void *closure) {
    ValuatorMask mask;
    lorieEvent *e = closure;
    double x = max(min((float) e->touch.x, pScreenPtr->width), 0);
    double y = max(min((float) e->touch.y, pScreenPtr->height), 0);
    valuator_mask_zero(&mask);
    DDXTouchPointInfoPtr touch = TouchFindByDDXID(lorieTouch, e->touch.id, FALSE);

    // Avoid duplicating events
    if (touch && touch->active) {
        double oldx = 0, oldy = 0;
        if (e->touch.type == XI_TouchUpdate &&
            valuator_mask_fetch_double(touch->valuators, 0, &oldx) &&
            valuator_mask_fetch_double(touch->valuators, 1, &oldy) &&
            oldx == x && oldy == y)
            goto end;
    }

    // Sometimes activity part does not send XI_TouchBegin and sends only XI_TouchUpdate.
    if (e->touch.type == XI_TouchUpdate && (!touch || !touch->active))
        e->touch.type = XI_TouchBegin;

    if (e->touch.type == XI_TouchEnd && (!touch || !touch->active))
        goto end;

    valuator_mask_set_double(&mask, 0, x * 0xFFFF / (float) pScreenPtr->width);
    valuator_mask_set_double(&mask, 1, y * 0xFFFF / (float) pScreenPtr->height);
    QueueTouchEvents(lorieTouch, e->touch.type, e->touch.id, 0, &mask);

    end:
    free(e);
    return TRUE;
}

void handleLorieEvents(int fd, __unused int ready, __unused void *ignored) {
    ValuatorMask mask;
    lorieEvent e = {0};
    valuator_mask_zero(&mask);

    if (ready & X_NOTIFY_ERROR) {
        LorieBuffer* buf;
        InputThreadUnregisterDev(fd);
        close(fd);
        conn_fd = -1;
        lorieEnableClipboardSync(FALSE);
        while ((buf = LorieBufferList_first(&registeredBuffers)))
            LorieBuffer_removeFromList(buf);
        return;
    }

    again:
    if (read(fd, &e, sizeof(e)) == sizeof(e)) {
        switch(e.type) {
            case EVENT_SCREEN_SIZE: {
                lorieEvent *copy = calloc(1, sizeof(lorieEvent) + e.screenSize.name_size + 1);
                memcpy(copy, &e, sizeof(e));
                copy->screenSize.name = copy->screenSize.name_size ? (char*) (copy + 1) : NULL;
                if (copy->screenSize.name_size)
                    read(fd, copy->screenSize.name, copy->screenSize.name_size);
                QueueWorkProc(sendConfigureNotify, NULL, copy);
                lorieTriggerWorkingQueue();
                break;
            }
            case EVENT_TOUCH: {
                lorieEvent *copy = calloc(1, sizeof(lorieEvent));
                memcpy(copy, &e, sizeof(e));
                QueueWorkProc(handleTouchEvent, NULL, copy);
                lorieTriggerWorkingQueue();
                break;
            }
            case EVENT_STYLUS: {
                static int buttons_prev = 0;
                uint32_t released, pressed, diff;
                DeviceIntPtr device = e.stylus.mouse ? lorieMouse : (e.stylus.eraser ? lorieEraser : loriePen);
                if (!device) {
                    __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "got stylus event but device is not requested\n");
                    break;
                }
                __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "got stylus event %f %f %d %d %d %d %s\n", e.stylus.x, e.stylus.y, e.stylus.pressure, e.stylus.tilt_x, e.stylus.tilt_y, e.stylus.orientation,
                                    device == lorieMouse ? "lorieMouse" : (device == loriePen ? "loriePen" : "lorieEraser"));

                valuator_mask_set_double(&mask, 0, max(min(e.stylus.x, pScreenPtr->width), 0));
                valuator_mask_set_double(&mask, 1, max(min(e.stylus.y, pScreenPtr->height), 0));
                if (device != lorieMouse) {
                    valuator_mask_set_double(&mask, 2, e.stylus.pressure);
                    valuator_mask_set_double(&mask, 3, e.stylus.tilt_x);
                    valuator_mask_set_double(&mask, 4, e.stylus.tilt_y);
                    valuator_mask_set_double(&mask, 5, e.stylus.orientation);
                }
                QueuePointerEvents(device, MotionNotify, 0, POINTER_ABSOLUTE | POINTER_DESKTOP | (device == lorieMouse ? POINTER_NORAW : 0), &mask);

                diff = buttons_prev ^ e.stylus.buttons;
                released = diff & ~e.stylus.buttons;
                pressed = diff & e.stylus.buttons;

                for (int i=0; i<3; i++) {
                    if (released & 0x1) {
                        QueuePointerEvents(device, ButtonRelease, i + 1, POINTER_RELATIVE, NULL);
                        __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "sending %d press", i+1);
                    }
                    if (pressed & 0x1) {
                        QueuePointerEvents(device, ButtonPress, i + 1, POINTER_RELATIVE, NULL);
                        __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "sending %d release", i+1);
                    }
                    released >>= 1;
                    pressed >>= 1;
                }
                buttons_prev = e.stylus.buttons;

                break;
            }
            case EVENT_STYLUS_ENABLE: {
                lorieSetStylusEnabled(e.stylusEnable.enable);
                break;
            }
            case EVENT_MOUSE: {
                int flags;
                switch(e.mouse.detail) {
                    case 0: // BUTTON_UNDEFINED
                        flags = (e.mouse.relative) ? POINTER_RELATIVE | POINTER_ACCELERATE : POINTER_ABSOLUTE | POINTER_SCREEN | POINTER_NORAW;
                        if (!e.mouse.relative) {
                            e.mouse.x = max(0, min(e.mouse.x, pScreenPtr->width));
                            e.mouse.y = max(0, min(e.mouse.y, pScreenPtr->height));
                        }
                        valuator_mask_set_double(&mask, 0, (double) e.mouse.x);
                        valuator_mask_set_double(&mask, 1, (double) e.mouse.y);
                        QueuePointerEvents(lorieMouse, MotionNotify, 0, flags, &mask);
                        break;
                    case 1: // BUTTON_LEFT
                    case 2: // BUTTON_MIDDLE
                    case 3: // BUTTON_RIGHT
                        QueuePointerEvents(lorieMouse, e.mouse.down ? ButtonPress : ButtonRelease, e.mouse.detail, POINTER_RELATIVE, NULL);
                        break;
                    case 4: // BUTTON_SCROLL
                        if (e.mouse.x) {
                            valuator_mask_zero(&mask);
                            valuator_mask_set_double(&mask, 2, (double) e.mouse.x / 120);
                            QueuePointerEvents(lorieMouse, MotionNotify, 0, POINTER_RELATIVE, &mask);
                        }
                        if (e.mouse.y) {
                            valuator_mask_zero(&mask);
                            valuator_mask_set_double(&mask, 3, (double) e.mouse.y / 120);
                            QueuePointerEvents(lorieMouse, MotionNotify, 0, POINTER_RELATIVE, &mask);
                        }
                        break;
                }
                break;
            }
            case EVENT_KEY:
                QueueKeyboardEvents(lorieKeyboard, e.key.state ? KeyPress : KeyRelease, e.key.key);
                break;
            case EVENT_UNICODE: {
                int ks = ucs2keysym((long) e.unicode.code);
                __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "Trying to input keysym %d\n", ks);
                lorieKeysymKeyboardEvent(ks, TRUE);
                lorieKeysymKeyboardEvent(ks, FALSE);
                break;
            }
            case EVENT_CLIPBOARD_ENABLE:
                lorieEnableClipboardSync(e.clipboardEnable.enable);
                break;
            case EVENT_CLIPBOARD_ANNOUNCE:
                QueueWorkProc(handleClipboardAnnounce, NULL, NULL);
                lorieTriggerWorkingQueue();
                break;
            case EVENT_CLIPBOARD_SEND: {
                char *data = calloc(1, e.clipboardSend.count + 1);
                read(conn_fd, data, e.clipboardSend.count);
                data[e.clipboardSend.count] = 0;
                QueueWorkProc(handleClipboardData, NULL, data);
                lorieTriggerWorkingQueue();
            }
        }

        int n;
        if (ioctl(fd, FIONREAD, &n) >= 0 && n > sizeof(e))
            goto again;
    }
}

void lorieSendClipboardData(const char* data) {
    if (data && conn_fd != -1) {
        size_t len = strlen(data);
        lorieEvent e = { .clipboardSend = { .t = EVENT_CLIPBOARD_SEND, .count = len } };
        write(conn_fd, &e, sizeof(e));
        write(conn_fd, data, len);
    }
}

void lorieRequestClipboard(void) {
    if (conn_fd != -1) {
        lorieEvent e = { .type = EVENT_CLIPBOARD_REQUEST };
        write(conn_fd, &e, sizeof(e));
    }
}

bool lorieConnectionAlive(void) {
    if (conn_fd == -1)
        return false;

    // Check if socket is closed or has errors.
    struct pollfd p = { .fd = conn_fd, .events = POLLIN | POLLHUP | POLLERR | POLLRDHUP };
    return !(poll(&p, 1, 0) == 1 && (p.revents & (POLLERR | POLLNVAL | POLLRDHUP | POLLHUP)));
}

static Bool addFd(__unused ClientPtr pClient, void *closure) {
    InputThreadRegisterDev((int) (int64_t) closure, handleLorieEvents, NULL);
    conn_fd = (int) (int64_t) closure;
    lorieActivityConnected();
    return TRUE;
}

void lorieSendSharedServerState(int memfd) {
    if (conn_fd != -1) {
        lorieEvent e = { .type = EVENT_SHARED_SERVER_STATE };
        write(conn_fd, &e, sizeof(e));
        ancil_send_fd(conn_fd, memfd);
    }
}

void lorieRegisterBuffer(LorieBuffer* buffer) {
    unsigned long id = LorieBuffer_description(buffer)->id;
    if (LorieBufferList_findById(&registeredBuffers, id))
        return; // Already registered

    if (conn_fd != -1 && buffer) {
        lorieEvent e = { .type = EVENT_ADD_BUFFER };
        write(conn_fd, &e, sizeof(e));
        LorieBuffer_sendHandleToUnixSocket(buffer, conn_fd);
        LorieBuffer_addToList(buffer, &registeredBuffers);
        const LorieBuffer_Desc* desc = LorieBuffer_description(buffer);
        log(INFO, "Received shared buffer width %d height %d format %d type %d id %llu", desc->width, desc->height, desc->format, desc->type, desc->id);
    }
}

void lorieUnregisterBuffer(LorieBuffer* buffer) {
    unsigned long id;
    if (!buffer || (!LorieBufferList_findById(&registeredBuffers, (id = LorieBuffer_description(buffer)->id))))
        return;  // Not exist or not registered so no need to unregister

    if (conn_fd != -1 && buffer) {
        lorieEvent e = { .removeBuffer = { .t = EVENT_REMOVE_BUFFER, .id = id } };
        write(conn_fd, &e, sizeof(e));
        LorieBuffer_removeFromList(buffer);
    }
}

void DDXNotifyFocusChanged(void) {
    if (conn_fd != -1) {
        lorieEvent e = { .type = EVENT_WINDOW_FOCUS_CHANGED };
        write(conn_fd, &e, sizeof(e));
    }
}

JNIEXPORT jobject JNICALL
Java_com_termux_x11_CmdEntryPoint_getXConnection(JNIEnv *env, __unused jobject cls) {
    int client[2];
    jclass ParcelFileDescriptorClass = (*env)->FindClass(env, "android/os/ParcelFileDescriptor");
    jmethodID adoptFd = (*env)->GetStaticMethodID(env, ParcelFileDescriptorClass, "adoptFd", "(I)Landroid/os/ParcelFileDescriptor;");
    socketpair(AF_UNIX, SOCK_STREAM, 0, client);
    QueueWorkProc(addFd, NULL, (void*) (int64_t) client[1]);
    lorieTriggerWorkingQueue();

    return (*env)->CallStaticObjectMethod(env, ParcelFileDescriptorClass, adoptFd, client[0]);
}

void* logcatThread(void *arg) {
    char buffer[4096];
    size_t len;
    while((len = read((int) (int64_t) arg, buffer, 4096)) >=0)
        write(2, buffer, len);
    close((int) (int64_t) arg);
    return NULL;
}

JNIEXPORT jobject JNICALL
Java_com_termux_x11_CmdEntryPoint_getLogcatOutput(JNIEnv *env, __unused jobject cls) {
    jclass ParcelFileDescriptorClass = (*env)->FindClass(env, "android/os/ParcelFileDescriptor");
    jmethodID adoptFd = (*env)->GetStaticMethodID(env, ParcelFileDescriptorClass, "adoptFd", "(I)Landroid/os/ParcelFileDescriptor;");
    const char *debug = getenv("TERMUX_X11_DEBUG");
    if (debug && !strcmp(debug, "1")) {
        pthread_t t;
        int p[2];
        pipe(p);
        fchmod(p[1], 0777);
        pthread_create(&t, NULL, logcatThread, (void*) (uint64_t) p[0]);
        return (*env)->CallStaticObjectMethod(env, ParcelFileDescriptorClass, adoptFd, p[1]);
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_termux_x11_CmdEntryPoint_connected(__unused JNIEnv *env, __unused jclass clazz) {
    return conn_fd != -1;
}

JNIEXPORT void JNICALL
Java_com_termux_x11_CmdEntryPoint_listenForConnections(JNIEnv *env, jobject thiz) {
    int server_fd, client, count;
    struct sockaddr_in address = { .sin_family = AF_INET, .sin_addr = { .s_addr = INADDR_ANY }, .sin_port = htons(PORT) };
    int addrlen = sizeof(address);
    jmethodID sendBroadcast = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, thiz), "sendBroadcast", "()V");
    uint8_t buffer[512] = {0};

    // Even in the case if it will fail for some reason everything will work fine
    // But connection will be delayed a bit

    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) == 0) {
        log(ERROR, "Socket creation failed: %s", strerror(errno));
        return;
    }

    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &(int){1}, sizeof(int));
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEPORT, &(int){1}, sizeof(int));

    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        log(ERROR, "Socket bind failed: %s", strerror(errno));
        close(server_fd);
        return;
    }

    if (listen(server_fd, 5) < 0) {
        log(ERROR, "Socket listen failed: %s", strerror(errno));
        close(server_fd);
        return;
    }

    while(1) {
        if ((client = accept(server_fd, (struct sockaddr *)&address, (socklen_t *)&addrlen)) < 0) {
            log(ERROR, "Socket accept failed: %s", strerror(errno));
            continue;
        }

        if ((count = read(client, buffer, sizeof(buffer))) > 0) {
            if (!memcmp(buffer, MAGIC, min(count, sizeof(MAGIC)))) {
                log(DEBUG, "New client connection!\n");
                (*env)->CallVoidMethod(env, thiz, sendBroadcast);
            }
        }
        close(client);
    }
}

void abort(void) {
    _exit(134);
}

void exit(int code) {
    _exit(code);
}
