#pragma once
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include "pixman.h"

#ifndef maybe_unused
#define maybe_unused __attribute__((__unused__))
#endif

#ifdef __cplusplus
extern "C" {
#endif

// X server is already linked to mesa so linking to Android's GLESv2 will confuse the linker.
// That is a reason why we should compile renderer as separate hared library with its own dependencies.
// In that case part of X server's api is unavailable,
// so we should pass addresses to all needed functions to the renderer lib.
typedef void (*renderer_message_func_type) (int type, int verb, const char *format, ...);

maybe_unused void renderer_message_func(renderer_message_func_type function);

maybe_unused int renderer_init(void);
maybe_unused void renderer_set_buffer(AHardwareBuffer* buffer);
maybe_unused void renderer_set_window(EGLNativeWindowType native_window);
maybe_unused void renderer_upload(int w, int h, void* data);
maybe_unused void renderer_update_rects(int width, int height, pixman_box16_t *rects, int amount, void* data);
maybe_unused void renderer_redraw(void);

maybe_unused void renderer_update_cursor(int w, int h, int xhot, int yhot, void* data);
maybe_unused void renderer_set_cursor_coordinates(int x, int y);

#ifdef __cplusplus
}
#endif
