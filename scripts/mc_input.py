#!/usr/bin/env python3
"""Smoke-test input helper: sends keys/clicks to the Minecraft window over X11.

Uses only ctypes + libX11/libXtst (no pip packages, no xdotool), so it works on a
plain XWayland/X11 session. Refuses to send anything unless the Minecraft window
actually owns the keyboard focus, so a stray keystroke can never land in another app.

Usage:
  mc_input.py focus                focus the Minecraft window (exit 1 if not found)
  mc_input.py geometry             print "<x> <y> <width> <height>"
  mc_input.py key <keysym>         e.g. key m / key Escape / key o
                                 prefixed combos work too: key shift+slash
  mc_input.py move <x> <y>         absolute pointer position (window relative)
  mc_input.py click <x> <y>        move + left click (window relative)
  mc_input.py shiftclick <x> <y>   move + shift+left click (window relative)
  mc_input.py scroll <dx> <dy>     wheel ticks at current position (+ = up/right)
"""
import ctypes
import ctypes.util
import sys
import time

DISPLAY_NAME = b"Minecraft"

x11 = ctypes.CDLL(ctypes.util.find_library("X11"))
xtst = ctypes.CDLL(ctypes.util.find_library("Xtst"))

x11.XOpenDisplay.restype = ctypes.c_void_p
x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
x11.XInternAtom.restype = ctypes.c_ulong
x11.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
x11.XDefaultRootWindow.restype = ctypes.c_ulong
x11.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
x11.XGetWindowProperty.restype = ctypes.c_int
x11.XGetWindowProperty.argtypes = [
    ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_long, ctypes.c_long,
    ctypes.c_int, ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_int),
    ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_ulong),
    ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte)),
]
x11.XFetchName.restype = ctypes.c_int
x11.XFetchName.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_char_p)]
x11.XGetInputFocus.restype = ctypes.c_int
x11.XGetInputFocus.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_int)]
x11.XSetInputFocus.restype = ctypes.c_int
x11.XSetInputFocus.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
x11.XGetGeometry.restype = ctypes.c_int
x11.XGetGeometry.argtypes = [
    ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_int),
    ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint),
    ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint),
]
x11.XTranslateCoordinates.restype = ctypes.c_int
x11.XTranslateCoordinates.argtypes = [
    ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_int, ctypes.c_int,
    ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_ulong),
]
x11.XStringToKeysym.restype = ctypes.c_ulong
x11.XStringToKeysym.argtypes = [ctypes.c_char_p]
x11.XKeysymToKeycode.restype = ctypes.c_ubyte
x11.XKeysymToKeycode.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
x11.XFlush.argtypes = [ctypes.c_void_p]

xtst.XTestFakeKeyEvent.restype = ctypes.c_int
xtst.XTestFakeKeyEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
xtst.XTestFakeButtonEvent.restype = ctypes.c_int
xtst.XTestFakeButtonEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
xtst.XTestFakeMotionEvent.restype = ctypes.c_int
xtst.XTestFakeMotionEvent.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_ulong]

RevertToParent = 2
CurrentTime = 0
AnyPropertyType = 0
False_ = 0


def open_display():
    dpy = x11.XOpenDisplay(None)
    if not dpy:
        sys.exit("cannot open X display")
    return dpy


def client_windows(dpy):
    root = x11.XDefaultRootWindow(dpy)
    atom = x11.XInternAtom(dpy, b"_NET_CLIENT_LIST", True)
    if not atom:
        return []
    actual_type = ctypes.c_ulong()
    actual_format = ctypes.c_int()
    nitems = ctypes.c_ulong()
    bytes_after = ctypes.c_ulong()
    prop = ctypes.POINTER(ctypes.c_ubyte)()
    status = x11.XGetWindowProperty(
        dpy, root, atom, 0, 1024, False_, AnyPropertyType,
        ctypes.byref(actual_type), ctypes.byref(actual_format), ctypes.byref(nitems),
        ctypes.byref(bytes_after), ctypes.byref(prop))
    if status != 0 or not prop:
        return []
    array = ctypes.cast(prop, ctypes.POINTER(ctypes.c_ulong))
    return [array[i] for i in range(nitems.value)]


def find_minecraft(dpy):
    for window in client_windows(dpy):
        name = ctypes.c_char_p()
        if x11.XFetchName(dpy, window, ctypes.byref(name)) and name.value:
            if DISPLAY_NAME.lower() in name.value.lower():
                return window
    return 0


def geometry(dpy, window):
    unused = ctypes.c_ulong()
    x = ctypes.c_int()
    y = ctypes.c_int()
    width = ctypes.c_uint()
    height = ctypes.c_uint()
    border = ctypes.c_uint()
    depth = ctypes.c_uint()
    x11.XGetGeometry(dpy, window, ctypes.byref(unused), ctypes.byref(x), ctypes.byref(y),
                     ctypes.byref(width), ctypes.byref(height), ctypes.byref(border), ctypes.byref(depth))
    root = x11.XDefaultRootWindow(dpy)
    out_x = ctypes.c_int()
    out_y = ctypes.c_int()
    child = ctypes.c_ulong()
    x11.XTranslateCoordinates(dpy, window, root, 0, 0, ctypes.byref(out_x), ctypes.byref(out_y),
                              ctypes.byref(child))
    return out_x.value, out_y.value, width.value, height.value


def focused_window(dpy):
    window = ctypes.c_ulong()
    revert = ctypes.c_int()
    x11.XGetInputFocus(dpy, ctypes.byref(window), ctypes.byref(revert))
    return window.value


def focus_minecraft(dpy):
    window = find_minecraft(dpy)
    if not window:
        print("minecraft window not found", file=sys.stderr)
        return 0
    x11.XSetInputFocus(dpy, window, RevertToParent, CurrentTime)
    x11.XFlush(dpy)
    time.sleep(0.3)
    if focused_window(dpy) != window:
        print("minecraft window did not take focus", file=sys.stderr)
        return 0
    return window


def press_key(dpy, keysym_name):
    # "shift+<keysym>" holds Shift_L around the press - needed for characters like
    # '/' that live on a shifted digit key on some keyboard layouts (e.g. Shift+7).
    shift = False
    if "+" in keysym_name:
        modifier, keysym_name = keysym_name.split("+", 1)
        shift = modifier.lower() == "shift"
    keysym = x11.XStringToKeysym(keysym_name.encode())
    if not keysym:
        sys.exit(f"unknown keysym {keysym_name}")
    keycode = x11.XKeysymToKeycode(dpy, keysym)
    if not keycode:
        sys.exit(f"no keycode for {keysym_name}")
    shiftcode = 0
    if shift:
        shiftcode = x11.XKeysymToKeycode(dpy, x11.XStringToKeysym(b"Shift_L"))
        xtst.XTestFakeKeyEvent(dpy, shiftcode, 1, 0)
        x11.XFlush(dpy)
    xtst.XTestFakeKeyEvent(dpy, keycode, 1, 0)
    xtst.XTestFakeKeyEvent(dpy, keycode, 0, 0)
    if shiftcode:
        xtst.XTestFakeKeyEvent(dpy, shiftcode, 0, 0)
    x11.XFlush(dpy)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    dpy = open_display()
    action = sys.argv[1]

    if action == "focus":
        sys.exit(0 if focus_minecraft(dpy) else 1)

    if action == "geometry":
        window = find_minecraft(dpy)
        if not window:
            sys.exit("minecraft window not found")
        print(*geometry(dpy, window))
        return

    window = focus_minecraft(dpy)
    if not window:
        sys.exit(1)
    win_x, win_y, _, _ = geometry(dpy, window)

    if action == "key":
        press_key(dpy, sys.argv[2])
        time.sleep(0.2)
    elif action == "move":
        x, y = int(sys.argv[2]), int(sys.argv[3])
        xtst.XTestFakeMotionEvent(dpy, -1, win_x + x, win_y + y, 0)
        x11.XFlush(dpy)
        time.sleep(0.2)
    elif action == "click":
        x, y = int(sys.argv[2]), int(sys.argv[3])
        xtst.XTestFakeMotionEvent(dpy, -1, win_x + x, win_y + y, 0)
        x11.XFlush(dpy)
        time.sleep(0.3)
        xtst.XTestFakeButtonEvent(dpy, 1, 1, 0)
        xtst.XTestFakeButtonEvent(dpy, 1, 0, 0)
        x11.XFlush(dpy)
        time.sleep(0.3)
    elif action == "shiftclick":
        x, y = int(sys.argv[2]), int(sys.argv[3])
        shiftcode = x11.XKeysymToKeycode(dpy, x11.XStringToKeysym(b"Shift_L"))
        xtst.XTestFakeMotionEvent(dpy, -1, win_x + x, win_y + y, 0)
        xtst.XTestFakeKeyEvent(dpy, shiftcode, 1, 0)
        x11.XFlush(dpy)
        time.sleep(0.3)
        xtst.XTestFakeButtonEvent(dpy, 1, 1, 0)
        xtst.XTestFakeButtonEvent(dpy, 1, 0, 0)
        x11.XFlush(dpy)
        time.sleep(0.3)
        xtst.XTestFakeKeyEvent(dpy, shiftcode, 0, 0)
        x11.XFlush(dpy)
        time.sleep(0.2)
    elif action == "scroll":
        dx, dy = int(sys.argv[2]), int(sys.argv[3])
        # X11 buttons 4/5 = vertical wheel (4 = up), 6/7 = horizontal (6 = left)
        for _ in range(abs(dy)):
            button = 4 if dy > 0 else 5
            xtst.XTestFakeButtonEvent(dpy, button, 1, 0)
            xtst.XTestFakeButtonEvent(dpy, button, 0, 0)
            x11.XFlush(dpy)
            time.sleep(0.05)
        for _ in range(abs(dx)):
            button = 7 if dx > 0 else 6
            xtst.XTestFakeButtonEvent(dpy, button, 1, 0)
            xtst.XTestFakeButtonEvent(dpy, button, 0, 0)
            x11.XFlush(dpy)
            time.sleep(0.05)
        time.sleep(0.3)
    else:
        sys.exit(f"unknown action {action}")


if __name__ == "__main__":
    main()
