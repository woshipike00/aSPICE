/* -*- Mode: C; c-basic-offset: 4; indent-tabs-mode: nil -*- */
/*
   Copyright (C) 2010 Red Hat, Inc.
   Copyright © 2006-2010 Collabora Ltd. <http://www.collabora.co.uk/>

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
#ifdef HAVE_CONFIG_H
# include "config.h"
#endif
#include <stdlib.h>
#include <string.h>
#include <glib-object.h>
#include "spice-util-priv.h"
#include "spice-util.h"
#include "spice-util-priv.h"

/**
 * SECTION:spice-util
 * @short_description: version and debugging functions
 * @title: Utilities
 * @section_id:
 * @stability: Stable
 * @include: spice-util.h
 *
 * Various functions for debugging and informational purposes.
 */

static gboolean debugFlag = FALSE;

/**
 * spice_util_set_debug:
 * @enabled: %TRUE or %FALSE
 *
 * Enable or disable Spice-GTK debugging messages.
 **/
void spice_util_set_debug(gboolean enabled)
{
#if GLIB_CHECK_VERSION(2, 31, 0)
    if (enabled) {
        const gchar *doms = g_getenv("G_MESSAGES_DEBUG");
        if (!doms) {
            g_setenv("G_MESSAGES_DEBUG", G_LOG_DOMAIN, 1);
        } else if (!strstr(doms, G_LOG_DOMAIN)) {
            gchar *newdoms = g_strdup_printf("%s %s", doms, G_LOG_DOMAIN);
            g_setenv("G_MESSAGES_DEBUG", newdoms, 1);
            g_free(newdoms);
        }
    }
#endif
    debugFlag = enabled;
}

gboolean spice_util_get_debug(void)
{
    return debugFlag || g_getenv("SPICE_DEBUG") != NULL;
}

/**
 * spice_util_get_version_string:
 *
 * Returns: Spice-GTK version as a const string.
 **/
const gchar *spice_util_get_version_string(void)
{
    return VERSION;
}

G_GNUC_INTERNAL
gboolean spice_strv_contains(const GStrv strv, const gchar *str)
{
    int i;

    if (strv == NULL)
        return FALSE;

    for (i = 0; strv[i] != NULL; i++)
        if (g_str_equal(strv[i], str))
            return TRUE;

    return FALSE;
}

G_GNUC_INTERNAL
gchar* spice_uuid_to_string(const guint8 uuid[16])
{
    return g_strdup_printf(UUID_FMT, uuid[0], uuid[1],
                           uuid[2], uuid[3], uuid[4], uuid[5],
                           uuid[6], uuid[7], uuid[8], uuid[9],
                           uuid[10], uuid[11], uuid[12], uuid[13],
                           uuid[14], uuid[15]);
}

typedef struct {
    GObject *instance;
    GObject *observer;
    GClosure *closure;
    gulong handler_id;
} WeakHandlerCtx;

static WeakHandlerCtx *
whc_new (GObject *instance,
         GObject *observer)
{
    WeakHandlerCtx *ctx = g_slice_new0 (WeakHandlerCtx);

    ctx->instance = instance;
    ctx->observer = observer;

    return ctx;
}

static void
whc_free (WeakHandlerCtx *ctx)
{
    g_slice_free (WeakHandlerCtx, ctx);
}

static void observer_destroyed_cb (gpointer, GObject *);
static void closure_invalidated_cb (gpointer, GClosure *);

/*
 * If signal handlers are removed before the object is destroyed, this
 * callback will never get triggered.
 */
static void
instance_destroyed_cb (gpointer ctx_,
                       GObject *where_the_instance_was)
{
    WeakHandlerCtx *ctx = ctx_;

    /* No need to disconnect the signal here, the instance has gone away. */
    g_object_weak_unref (ctx->observer, observer_destroyed_cb, ctx);
    g_closure_remove_invalidate_notifier (ctx->closure, ctx,
                                          closure_invalidated_cb);
    whc_free (ctx);
}

/* Triggered when the observer is destroyed. */
static void
observer_destroyed_cb (gpointer ctx_,
                       GObject *where_the_observer_was)
{
    WeakHandlerCtx *ctx = ctx_;

    g_closure_remove_invalidate_notifier (ctx->closure, ctx,
                                          closure_invalidated_cb);
    g_signal_handler_disconnect (ctx->instance, ctx->handler_id);
    g_object_weak_unref (ctx->instance, instance_destroyed_cb, ctx);
    whc_free (ctx);
}

/* Triggered when either object is destroyed or the handler is disconnected. */
static void
closure_invalidated_cb (gpointer ctx_,
                        GClosure *where_the_closure_was)
{
    WeakHandlerCtx *ctx = ctx_;

    g_object_weak_unref (ctx->instance, instance_destroyed_cb, ctx);
    g_object_weak_unref (ctx->observer, observer_destroyed_cb, ctx);
    whc_free (ctx);
}

/* Copied from tp_g_signal_connect_object. See documentation. */
/**
  * spice_g_signal_connect_object: (skip)
  * @instance: the instance to connect to.
  * @detailed_signal: a string of the form "signal-name::detail".
  * @c_handler: the #GCallback to connect.
  * @gobject: the object to pass as data to @c_handler.
  * @connect_flags: a combination of #GConnectFlags.
  *
  * Similar to g_signal_connect_object() but will delete connection
  * when any of the objects is destroyed.
  *
  * Returns: the handler id.
  */
gulong spice_g_signal_connect_object (gpointer instance,
                                      const gchar *detailed_signal,
                                      GCallback c_handler,
                                      gpointer gobject,
                                      GConnectFlags connect_flags)
{
    GObject *instance_obj = G_OBJECT (instance);
    WeakHandlerCtx *ctx = whc_new (instance_obj, gobject);

    g_return_val_if_fail (G_TYPE_CHECK_INSTANCE (instance), 0);
    g_return_val_if_fail (detailed_signal != NULL, 0);
    g_return_val_if_fail (c_handler != NULL, 0);
    g_return_val_if_fail (G_IS_OBJECT (gobject), 0);
    g_return_val_if_fail (
                          (connect_flags & ~(G_CONNECT_AFTER|G_CONNECT_SWAPPED)) == 0, 0);

    if (connect_flags & G_CONNECT_SWAPPED)
        ctx->closure = g_cclosure_new_object_swap (c_handler, gobject);
    else
        ctx->closure = g_cclosure_new_object (c_handler, gobject);

    ctx->handler_id = g_signal_connect_closure (instance, detailed_signal,
                                                ctx->closure, (connect_flags & G_CONNECT_AFTER) ? TRUE : FALSE);

    g_object_weak_ref (instance_obj, instance_destroyed_cb, ctx);
    g_object_weak_ref (gobject, observer_destroyed_cb, ctx);
    g_closure_add_invalidate_notifier (ctx->closure, ctx,
                                       closure_invalidated_cb);

    return ctx->handler_id;
}

G_GNUC_INTERNAL
const gchar* spice_yes_no(gboolean value)
{
    return value ? "yes" : "no";
}

G_GNUC_INTERNAL
guint16 spice_make_scancode(guint scancode, gboolean release)
{
    SPICE_DEBUG("%s: %s scancode %d",
                __FUNCTION__, release ? "release" : "", scancode);

    if (release) {
        if (scancode < 0x100)
            return scancode | 0x80;
        else
            return 0x80e0 | ((scancode - 0x100) << 8);
    } else {
        if (scancode < 0x100)
            return scancode;
        else
            return 0xe0 | ((scancode - 0x100) << 8);
    }

    g_return_val_if_reached(0);
}
