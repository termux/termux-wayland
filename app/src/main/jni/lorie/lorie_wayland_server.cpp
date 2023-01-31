#include <lorie_wayland_server.hpp>
using namespace wayland;

#pragma ide diagnostic ignored "cppcoreguidelines-pro-type-static-cast-downcast"

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

class fd_listener: public wl_listener {
protected:
    std::function<int(int, uint32_t)> dispatch;
    static void destroy(wl_listener* that, void *) {
        auto listener = static_cast<fd_listener*>(that);
        wl_event_source_remove(listener->source);
        delete listener;
    }
public:
    wl_event_source* source = nullptr;
    explicit fd_listener(wl_event_loop* loop, std::function<int(int, uint32_t)> dispatch_func):
            wl_listener{{}, &destroy}, dispatch(std::move(dispatch_func)) {
        wl_event_loop_add_destroy_listener(loop, this);
    }
    static int fire(int fd, uint32_t mask, void *data) {
        auto listener = static_cast<fd_listener*>(data);
        if (listener)
            return listener->dispatch(fd, mask);
        return 0;
    }
};

void display_t::add_fd_listener(int fd, uint32_t mask, std::function<int(int fd, uint32_t mask)> listener) {
    wl_event_loop* loop = wl_display_get_event_loop(display);
    auto l = new fd_listener(loop, std::move(listener));
    l->source = wl_event_loop_add_fd(loop, fd, mask, fd_listener::fire, l);
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

void client_t::post_implementation_error(const std::string& string) {
    wl_client_post_implementation_error(client, "%s", string.c_str());
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
resource(r), version(1) {
    wl_resource_add_destroy_listener(resource, this);
}

uint32_t resource_t::timestamp() {
    timespec t = {0};
    clock_gettime (CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
