#include <lorie_wayland_server.hpp>
#include <cerrno>
#include <linux/un.h>
#include <unistd.h>
#include <sys/socket.h>

#pragma clang diagnostic ignored "-Wshadow"
#pragma ide diagnostic ignored "cppcoreguidelines-pro-type-static-cast-downcast"

using namespace wayland;


/* display_t methods */
static void display_destroyed(wl_listener* l, void* data) {
    auto d = static_cast<display_t*>(l);
    if (d->on_destroy)
        d->on_destroy();
}

static void client_created(wl_listener* l, void* data) {
    if (!data) return;
    auto c = static_cast<wl_client*>(data);
    auto d = reinterpret_cast<display_t*>(wl_display_get_destroy_listener(wl_client_get_display(c), &display_destroyed));
    auto new_client = new client_t(c);
    if (d->on_client)
        d->on_client(new_client);
}

display_t::display_t():
display(wl_display_create()),
wl_listener{{}, &display_destroyed},
client_created_listener{{}, &client_created}{
    wl_display_add_destroy_listener(display, this);
    wl_display_add_client_created_listener(display, &client_created_listener);
}

display_t::~display_t() {
    wl_display_destroy_clients(display);
    wl_display_destroy(display);
}

/* client_t methods */
wl_listener client_resource_created {{}, [](wl_listener*, void* d) {
    if (d == nullptr || *(static_cast<wl_interface**>(d)) != &wl_buffer_interface)
        return;
    new buffer_t(static_cast<wl_resource*>(d));
}};

static void client_destroy_callback(struct wl_listener *listener, void *) {
    if (listener == nullptr) return;
    auto c = static_cast<client_t*>(listener);
    if (c->on_destroy)
        c->on_destroy();
    delete c;
};

client_t::client_t(wl_client* client): wl_listener{{}, &client_destroy_callback}, client(client) {
    wl_client_add_destroy_listener(*this, this);
    wl_client_add_resource_created_listener(*this, &client_resource_created);
}

client_t* client_t::get(wl_client* client) {
    return client ? static_cast<client_t*>(wl_client_get_destroy_listener(client, &client_destroy_callback)) : nullptr;
}

void client_t::destroy() {
    wl_client_destroy(client);
}

/* resource_t methods */
void resource_t::resource_destroyed(wl_listener* that, void*) {
    auto r = static_cast<resource_t*>(that);
    if (r->on_destroy)
        r->on_destroy();
    delete r;
}

resource_t::resource_t(client_t* client, uint32_t id, uint32_t version,
wl_interface* iface, wl_dispatcher_func_t dispatcher):
wl_listener{{}, &resource_destroyed},
m_client(client), display(wl_client_get_display(*client)),
resource(wl_resource_create(*client, iface, version, id)),
version(version) {
    wl_resource_add_destroy_listener(resource, this);
    wl_resource_set_dispatcher(resource, dispatcher, iface, nullptr, nullptr);
}

static inline client_t* client_get(wl_resource* c) {
    wl_client* client;
    if (c == nullptr || (client = wl_resource_get_client(c)) == nullptr)
        return nullptr;
    return client_t::get(client);
}

resource_t::resource_t(wl_resource *r):
wl_listener{{}, &resource_destroyed},
m_client(client_get(r)), display(wl_client_get_display(*m_client)),
resource(r), version(wl_resource_get_version(r)) {
    wl_resource_add_destroy_listener(resource, this);
}