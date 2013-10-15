/* -*- Mode: C; c-basic-offset: 4; indent-tabs-mode: nil -*- */
/*
   Copyright (C) 2012 Red Hat, Inc.

   This library is free software; you can redistribute it and/or
   modify it under the terms of the GNU Lesser General Public
   License as published by the Free Software Foundation; either
   version 2.1 of the License, or (at your option) any later version.

   This library is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
   Lesser General Public License for more details.

   You should have received a copy of the GNU Lesser General Public
   License along with this library; if not, see <http://www.gnu.org/licenses/>.
*/
#include "spice-client.h"
#include "spice-common.h"
#include "spice-channel-priv.h"
#include "spice-marshal.h"
#include "glib-compat.h"

/**
 * SECTION:channel-port
 * @short_description: private communication channel
 * @title: Port Channel
 * @section_id:
 * @see_also: #SpiceChannel
 * @stability: Stable
 * @include: channel-port.h
 *
 * A Spice port channel carry arbitrary data between the Spice client
 * and the Spice server. It may be used to provide additional
 * services on top of a Spice connection. For example, a channel can
 * be associated with the qemu monitor for the client to interact
 * with it, just like any qemu chardev. Or it may be used with
 * various protocols, such as the Spice Controller.
 *
 * A port kind is identified simply by a fqdn, such as
 * org.qemu.monitor, org.spice.spicy.test or org.ovirt.controller...
 *
 * Once connected and initialized, the client may read the name of the
 * port via SpicePortChannel:port-name.

 * When the other end of the port is ready,
 * SpicePortChannel:port-opened is set to %TRUE and you can start
 * receiving data via the signal SpicePortChannel::port-data, or
 * sending data via spice_port_write_async().
 *
 * Since: 0.15
 */

#define SPICE_PORT_CHANNEL_GET_PRIVATE(obj)                                  \
    (G_TYPE_INSTANCE_GET_PRIVATE((obj), SPICE_TYPE_PORT_CHANNEL, SpicePortChannelPrivate))

struct _SpicePortChannelPrivate {
    gchar *name;
    gboolean opened;
};

G_DEFINE_TYPE(SpicePortChannel, spice_port_channel, SPICE_TYPE_CHANNEL)

/* Properties */
enum {
    PROP_0,
    PROP_PORT_NAME,
    PROP_PORT_OPENED,
};

/* Signals */
enum {
    SPICE_PORT_DATA,
    SPICE_PORT_EVENT,
    LAST_SIGNAL,
};

static guint signals[LAST_SIGNAL];

static void spice_port_handle_msg(SpiceChannel *channel, SpiceMsgIn *msg);

static void spice_port_channel_init(SpicePortChannel *channel)
{
    channel->priv = SPICE_PORT_CHANNEL_GET_PRIVATE(channel);
}

static void spice_port_get_property(GObject    *object,
                                      guint       prop_id,
                                      GValue     *value,
                                      GParamSpec *pspec)
{
    SpicePortChannelPrivate *c = SPICE_PORT_CHANNEL(object)->priv;

    switch (prop_id) {
    case PROP_PORT_NAME:
        g_value_set_string(value, c->name);
        break;
    case PROP_PORT_OPENED:
        g_value_set_boolean(value, c->opened);
        break;
    default:
        G_OBJECT_WARN_INVALID_PROPERTY_ID(object, prop_id, pspec);
        break;
    }
}

static void spice_port_channel_finalize(GObject *object)
{
    SpicePortChannelPrivate *c = SPICE_PORT_CHANNEL(object)->priv;

    g_free(c->name);

    if (G_OBJECT_CLASS(spice_port_channel_parent_class)->finalize)
        G_OBJECT_CLASS(spice_port_channel_parent_class)->finalize(object);
}

static void spice_port_channel_reset(SpiceChannel *channel, gboolean migrating)
{
    SpicePortChannelPrivate *c = SPICE_PORT_CHANNEL(channel)->priv;

    g_clear_pointer(&c->name, g_free);
    c->opened = FALSE;

    SPICE_CHANNEL_CLASS(spice_port_channel_parent_class)->channel_reset(channel, migrating);
}

static void spice_port_channel_class_init(SpicePortChannelClass *klass)
{
    GObjectClass *gobject_class = G_OBJECT_CLASS(klass);
    SpiceChannelClass *channel_class = SPICE_CHANNEL_CLASS(klass);

    gobject_class->finalize     = spice_port_channel_finalize;
    gobject_class->get_property = spice_port_get_property;
    channel_class->handle_msg   = spice_port_handle_msg;
    channel_class->channel_reset = spice_port_channel_reset;

    g_object_class_install_property
        (gobject_class, PROP_PORT_NAME,
         g_param_spec_string("port-name",
                             "Port name",
                             "Port name",
                             NULL,
                             G_PARAM_READABLE | G_PARAM_STATIC_STRINGS));

    g_object_class_install_property
        (gobject_class, PROP_PORT_OPENED,
         g_param_spec_boolean("port-opened",
                              "Port opened",
                              "Port opened",
                              FALSE,
                              G_PARAM_READABLE | G_PARAM_STATIC_STRINGS));

    /**
     * SpicePort::port-data:
     * @channel: the channel that emitted the signal
     * @data: the data received
     * @size: number of bytes read
     *
     * The #SpicePortChannel::port-data signal is emitted when new
     * port data is received.
     * Since: 0.15
     **/
    signals[SPICE_PORT_DATA] =
        g_signal_new("port-data",
                     G_OBJECT_CLASS_TYPE(gobject_class),
                     G_SIGNAL_RUN_LAST,
                     0,
                     NULL, NULL,
                     g_cclosure_user_marshal_VOID__POINTER_INT,
                     G_TYPE_NONE,
                     2,
                     G_TYPE_POINTER, G_TYPE_INT);


    /**
     * SpicePort::port-event:
     * @channel: the channel that emitted the signal
     * @event: the event received
     * @size: number of bytes read
     *
     * The #SpicePortChannel::port-event signal is emitted when new
     * port event is received.
     * Since: 0.15
     **/
    signals[SPICE_PORT_EVENT] =
        g_signal_new("port-event",
                     G_OBJECT_CLASS_TYPE(gobject_class),
                     G_SIGNAL_RUN_LAST,
                     0,
                     NULL, NULL,
                     g_cclosure_marshal_VOID__INT,
                     G_TYPE_NONE,
                     1,
                     G_TYPE_INT);

    g_type_class_add_private(klass, sizeof(SpicePortChannelPrivate));
}

/* signal trampoline---------------------------------------------------------- */

struct SPICE_PORT_DATA {
    uint8_t *data;
    gsize data_size;
};

struct SPICE_PORT_EVENT {
    int event;
};

/* main context */
static void do_emit_main_context(GObject *object, int signum, gpointer params)
{
    switch (signum) {
    case SPICE_PORT_DATA: {
        struct SPICE_PORT_DATA *p = params;
        g_signal_emit(object, signals[signum], 0, p->data, p->data_size);
        break;
    }
    case SPICE_PORT_EVENT: {
        struct SPICE_PORT_EVENT *p = params;
        g_signal_emit(object, signals[signum], 0, p->event);
        break;
    }
    default:
        g_warn_if_reached();
    }
}

/* coroutine context */
static void port_set_opened(SpicePortChannel *self, gboolean opened)
{
    SpicePortChannelPrivate *c = self->priv;

    if (c->opened == opened)
        return;

    c->opened = opened;
    g_object_notify_main_context(G_OBJECT(self), "port-opened");
}

/* coroutine context */
static void port_handle_init(SpiceChannel *channel, SpiceMsgIn *in)
{
    SpicePortChannel *self = SPICE_PORT_CHANNEL(channel);
    SpicePortChannelPrivate *c = self->priv;
    SpiceMsgPortInit *init = spice_msg_in_parsed(in);

    CHANNEL_DEBUG(channel, "init: %s %d", init->name, init->opened);
    g_return_if_fail(init->name != NULL && *init->name != '\0');
    g_return_if_fail(c->name == NULL);

    c->name = g_strdup((gchar*)init->name);
    g_object_notify(G_OBJECT(channel), "port-name");

    port_set_opened(self, init->opened);
}

/* coroutine context */
static void port_handle_event(SpiceChannel *channel, SpiceMsgIn *in)
{
    SpicePortChannel *self = SPICE_PORT_CHANNEL(channel);
    SpiceMsgPortEvent *event = spice_msg_in_parsed(in);

    CHANNEL_DEBUG(channel, "port event: %d", event->event);
    switch (event->event) {
    case SPICE_PORT_EVENT_OPENED:
        port_set_opened(self, true);
        break;
    case SPICE_PORT_EVENT_CLOSED:
        port_set_opened(self, false);
        break;
    }

    emit_main_context(channel, SPICE_PORT_EVENT, event->event);
}

/* coroutine context */
static void port_handle_msg(SpiceChannel *channel, SpiceMsgIn *in)
{
    SpicePortChannel *self = SPICE_PORT_CHANNEL(channel);
    int size;
    uint8_t *buf;

    buf = spice_msg_in_raw(in, &size);
    CHANNEL_DEBUG(channel, "port %p got %d %p", channel, size, buf);
    port_set_opened(self, true);
    emit_main_context(channel, SPICE_PORT_DATA, buf, size);
}

static void port_write_free_cb(uint8_t *data, void *user_data)
{
    GSimpleAsyncResult *result = user_data;

    g_simple_async_result_complete(result);
    g_object_unref(result);
}

/**
 * spice_port_write_async:
 * @port: A #SpicePortChannel
 * @buffer: (array length=count) (element-type guint8): the buffer
 * containing the data to write
 * @count: the number of bytes to write
 * @cancellable: (allow-none): optional GCancellable object, NULL to ignore
 * @callback: (scope async): callback to call when the request is satisfied
 * @user_data: (closure): the data to pass to callback function
 *
 * Request an asynchronous write of count bytes from @buffer into the
 * @port. When the operation is finished @callback will be called. You
 * can then call spice_port_write_finish() to get the result of
 * the operation.
 *
 * Since: 0.15
 **/
void spice_port_write_async(SpicePortChannel *self,
                            const void *buffer, gsize count,
                            GCancellable *cancellable,
                            GAsyncReadyCallback callback,
                            gpointer user_data)
{
    GSimpleAsyncResult *simple;
    SpicePortChannelPrivate *c;
    SpiceMsgOut *msg;

    g_return_if_fail(SPICE_IS_PORT_CHANNEL(self));
    g_return_if_fail(buffer != NULL);
    c = self->priv;

    if (!c->opened) {
        g_simple_async_report_error_in_idle(G_OBJECT(self), callback, user_data,
            SPICE_CLIENT_ERROR, SPICE_CLIENT_ERROR_FAILED,
            "The port is not opened");
        return;
    }

    simple = g_simple_async_result_new(G_OBJECT(self), callback, user_data,
                                       spice_port_write_async);
    g_simple_async_result_set_op_res_gssize(simple, count);

    msg = spice_msg_out_new(SPICE_CHANNEL(self), SPICE_MSGC_SPICEVMC_DATA);
    spice_marshaller_add_ref_full(msg->marshaller, (uint8_t*)buffer, count,
                                  port_write_free_cb, simple);
    spice_msg_out_send(msg);
}

/**
 * spice_port_write_finish:
 * @port: a #SpicePortChannel
 * @result: a #GAsyncResult
 * @error: a #GError location to store the error occurring, or %NULL
 * to ignore
 *
 * Finishes a port write operation.
 *
 * Returns: a #gssize containing the number of bytes written to the stream.
 * Since: 0.15
 **/
gssize spice_port_write_finish(SpicePortChannel *self,
                               GAsyncResult *result, GError **error)
{
    GSimpleAsyncResult *simple;

    g_return_val_if_fail(SPICE_IS_PORT_CHANNEL(self), -1);
    g_return_val_if_fail(result != NULL, -1);

    simple = (GSimpleAsyncResult *)result;

    if (g_simple_async_result_propagate_error(simple, error))
        return -1;

    g_return_val_if_fail(g_simple_async_result_is_valid(result, G_OBJECT(self),
                                                        spice_port_write_async), -1);

    return g_simple_async_result_get_op_res_gssize(simple);
}

/**
 * spice_port_event:
 * @port: a #SpicePortChannel
 * @event: a SPICE_PORT_EVENT value
 *
 * Send an event to the port.
 *
 * Note: The values SPICE_PORT_EVENT_CLOSED and
 * SPICE_PORT_EVENT_OPENED are managed by the channel connection
 * state.
 *
 * Since: 0.15
 **/
void spice_port_event(SpicePortChannel *self, guint8 event)
{
    SpiceMsgcPortEvent e;
    SpiceMsgOut *msg;

    g_return_if_fail(SPICE_IS_PORT_CHANNEL(self));
    g_return_if_fail(event > SPICE_PORT_EVENT_CLOSED);

    msg = spice_msg_out_new(SPICE_CHANNEL(self), SPICE_MSGC_PORT_EVENT);
    e.event = event;
    msg->marshallers->msgc_port_event(msg->marshaller, &e);
    spice_msg_out_send(msg);
}

static const spice_msg_handler port_handlers[] = {
    [ SPICE_MSG_PORT_INIT ]              = port_handle_init,
    [ SPICE_MSG_PORT_EVENT ]             = port_handle_event,
    [ SPICE_MSG_SPICEVMC_DATA ]          = port_handle_msg,
};

/* coroutine context */
static void spice_port_handle_msg(SpiceChannel *channel, SpiceMsgIn *msg)
{
    int type = spice_msg_in_type(msg);
    SpiceChannelClass *parent_class;

    g_return_if_fail(type < SPICE_N_ELEMENTS(port_handlers));

    parent_class = SPICE_CHANNEL_CLASS(spice_port_channel_parent_class);

    if (port_handlers[type] != NULL)
        port_handlers[type](channel, msg);
    else if (parent_class->handle_msg)
        parent_class->handle_msg(channel, msg);
    else
        g_return_if_reached();
}
