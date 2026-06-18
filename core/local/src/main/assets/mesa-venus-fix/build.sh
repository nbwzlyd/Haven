#!/bin/sh
# Build a patched Mesa zink (libgallium) in-guest and cache it for the venus
# GPU path. The patch (zink-cbuf-coherency.patch) re-issues the per-frame UBO
# through the command stream so the model stops flickering off-screen over
# venus' unreliable host-visible memory (see project_virgl_cage_gpu_accel R5).
#
# Built from the distro's OWN mesa source (apt-get source) so the result is
# ABI-identical to the system libgallium except for our hunk; LD_PRELOADing it
# overrides the loader's dlopen of the same soname. No new dependency is left
# behind beyond the cached .so. Idempotent: a populated `preload` short-circuits.
#
# Cost: pulls mesa build-deps + source and compiles zink (~hundreds of MB,
# ~20-40 min on a phone, needs network). Intended to run once per distro,
# triggered explicitly (Settings / run_in_proot), never on the hot launch path.
set -e

PREFIX=/usr/local/lib/haven/mesa-venus-fix
PATCH=/usr/local/share/haven/mesa-venus-fix/mesa-venus-gl.patch
mkdir -p "$PREFIX"
# Output goes to stdout/stderr; the caller (run_in_proot, or the Settings
# trigger redirecting to build.log) decides how to capture/persist it.
echo "=== mesa-venus-fix build $(date 2>/dev/null) ==="

if [ -s "$PREFIX/preload" ]; then
  echo "already built: $(cat "$PREFIX/preload")"
  exit 0
fi
[ -f "$PATCH" ] || { echo "FAIL: patch not staged at $PATCH"; exit 2; }

if command -v apt-get >/dev/null 2>&1; then
  FAMILY=apt
else
  echo "UNSUPPORTED: only APT distros (Debian/Ubuntu) are wired today."
  echo "The venus GL coherency fix needs a patched in-guest Mesa; this distro"
  echo "uses a different source/build system. GL still works, just flickery."
  exit 10
fi

export DEBIAN_FRONTEND=noninteractive

# Enable deb-src in whichever apt format the distro uses.
if [ -f /etc/apt/sources.list ]; then
  sed -i 's/^# *deb-src/deb-src/' /etc/apt/sources.list || true
  if ! grep -q '^deb-src' /etc/apt/sources.list; then
    sed -n 's/^deb \(.*\)/deb-src \1/p' /etc/apt/sources.list >> /etc/apt/sources.list || true
  fi
fi
for f in /etc/apt/sources.list.d/*.sources; do
  [ -f "$f" ] || continue
  grep -q '^Types:.*deb-src' "$f" || sed -i 's/^\(Types: .*deb\)$/\1 deb-src/' "$f" || true
done

echo "--- apt-get update"
apt-get update
echo "--- build-dep + tools"
apt-get install -y --no-install-recommends dpkg-dev meson ninja-build pkg-config gcc g++ patch python3 python3-pip
apt-get build-dep -y mesa

# Recent Mesa needs meson >= 1.4; Ubuntu noble ships 1.3.2. Upgrade via pip if
# the apt meson is too old (PEP-668 needs --break-system-packages on noble).
MESON_V=$(meson --version 2>/dev/null || echo 0)
case "$MESON_V" in
  0|0.*|1.0.*|1.1.*|1.2.*|1.3.*)
    echo "--- meson $MESON_V too old, pip-upgrading"
    pip3 install --break-system-packages -U meson 2>/dev/null || pip3 install -U meson || true
    hash -r 2>/dev/null || true
    echo "--- meson now $(meson --version 2>/dev/null)"
    ;;
esac

WORK=/tmp/mesa-venus-build
rm -rf "$WORK"; mkdir -p "$WORK"; cd "$WORK"
echo "--- apt-get source mesa"
apt-get source mesa
SRC=$(find . -maxdepth 1 -type d -name 'mesa-*' | head -1)
[ -n "$SRC" ] || { echo "FAIL: no mesa-* source dir"; exit 3; }
cd "$SRC"
echo "--- applying $PATCH"
patch -p1 < "$PATCH" || { echo "FAIL: patch did not apply (mesa version drift?)"; exit 4; }

echo "--- meson setup (zink only)"
meson setup _b \
  -Dgallium-drivers=zink \
  -Dvulkan-drivers= \
  -Dllvm=disabled \
  -Dglx=disabled \
  -Dplatforms=wayland \
  -Degl=enabled -Dgbm=enabled -Dgles2=enabled -Dopengl=true \
  -Dbuildtype=release
echo "--- ninja (this is the long part)"
ninja -C _b

GAL=$(find _b -name 'libgallium-*.so' -type f | head -1)
# libEGL is libEGL_mesa.so.0* on a glvnd distro (Ubuntu/Debian) or libEGL.so.1*
# without glvnd; preload whichever the build produced — soname dedup makes the
# loader (or the glvnd dispatcher) resolve to our patched copy either way.
EGL=$(find _b \( -name 'libEGL_mesa.so.0*' -o -name 'libEGL.so.1*' \) -type f | head -1)
[ -n "$GAL" ] || { echo "FAIL: built libgallium not found"; exit 5; }
[ -n "$EGL" ] || { echo "FAIL: built libEGL not found"; exit 5; }
cp -f "$GAL" "$EGL" "$PREFIX/"
# Companion libs too (harmless extras; not in the preload below).
for p in 'libgbm.so*' 'libGLESv2*.so*'; do
  f=$(find _b -name "$p" -type f | head -1)
  [ -n "$f" ] && cp -f "$f" "$PREFIX/" || true
done

# preload = patched libgallium + libEGL (device-verified minimal set, R5).
#  - libgallium carries the zink per-frame-UBO coherency fix.
#  - libEGL carries the platform_wayland.c routing fix that lands zink+venus on
#    the kopper sw-WSI present path over a wl_shm-only compositor (without it
#    nothing is presented at all).
# Both share the system soname, so the Mesa loader's dlopen/link resolves to
# these preloaded copies. Space-separated (LD_PRELOAD accepts space or colon).
printf '%s %s' "$PREFIX/$(basename "$GAL")" "$PREFIX/$(basename "$EGL")" > "$PREFIX/preload"
echo "=== done. preload=$(cat "$PREFIX/preload") ==="
