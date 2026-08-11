#!/usr/bin/env bash
set -euo pipefail

# Cuttlefish host setup. NEEDS ROOT, and ends in a reboot.
#
# Why Cuttlefish rather than the AVD emulator: the emulator's -gpu host path
# requires a window (`sRendererUsesSubWindow=1`), so a headless session is
# forced onto swiftshader, and swiftshader cannot sustain rendering wash's
# shell. Cuttlefish is built for headless servers — gfxstream marshals guest
# GL/Vulkan to the host GPU with no window, and its UI is a web server, so the
# machine running the device needs no display at all.
#
# It also earns its keep later: the SMS and QR-pairing work at 0.7 needs two
# devices talking to each other, which Cuttlefish does natively (modem
# simulator, Rootcanal) and the AVD does not.
#
# What this installs:
#   cuttlefish-base   device resources, kernel modules, network bridges, udev
#   cuttlefish-user   the local web server that serves the device UI
#
# The reboot is not optional: it is what loads the added kernel modules
# (vhost_vsock, vhost_net) and applies the new udev rules.

if [[ $EUID -ne 0 ]]; then
  echo "install-cuttlefish: run me with sudo." >&2
  echo "  sudo $0" >&2
  exit 1
fi

TARGET_USER="${SUDO_USER:-$USER}"

echo "==> registering the Artifact Registry apt repo"
curl -fsSL https://us-apt.pkg.dev/doc/repo-signing-key.gpg \
  -o /etc/apt/trusted.gpg.d/artifact-registry.asc
chmod a+r /etc/apt/trusted.gpg.d/artifact-registry.asc

if ! grep -qs "android-cuttlefish-artifacts" /etc/apt/sources.list.d/artifact-registry.list 2>/dev/null; then
  echo "deb https://us-apt.pkg.dev/projects/android-cuttlefish-artifacts android-cuttlefish main" \
    >> /etc/apt/sources.list.d/artifact-registry.list
fi

apt-get update

echo "==> installing cuttlefish-base + cuttlefish-user"
apt-get install -y cuttlefish-base cuttlefish-user

echo "==> adding $TARGET_USER to kvm, cvdnetwork, render"
usermod -aG kvm,cvdnetwork,render "$TARGET_USER"

# ---- serve the device UI on the LAN, not loopback -------------------------
#
# The operator defaults to 127.0.0.1:1080 (HTTP). Two changes:
#
#   listen_addr=0.0.0.0   reachable from the LAN, matching how wash's other
#                         dev servers bind
#   https_port=16000      TLS, and not because the traffic is secret: a
#                         browser on another machine treats a plain-HTTP
#                         origin as insecure, and RTCPeerConnection is
#                         secure-context-only — so over HTTP the device screen
#                         simply would not render. Self-signed cert, one
#                         browser warning.
#
# (8443 belongs to the separate `operator_proxy`; the operator serves TLS
# itself, so we do not need it.)
#
# Set through /etc/default/cuttlefish-operator, which /etc/init.d/cuttlefish-operator
# sources, and NOT through a systemd drop-in. A drop-in has to override ExecStart
# to add the flags, and overriding ExecStart discards everything upstream's start()
# does around the binary. Each loss is silent and none of them looks like its cause:
#
#   --chuid _cutf-operator:cvdnetwork  the operator runs as root instead, so
#                                      /run/cuttlefish/operator comes out root:root
#                                      0770. The device's webRTC process runs as
#                                      you, cannot connect, and never registers —
#                                      the UI then shows no devices however healthy
#                                      the guest is.
#   --chdir /usr/share/cuttlefish-common/operator
#                                      static assets are served relative to cwd, and
#                                      systemd starts services in /. The UI answers
#                                      "404 page not found" at its own root.
#   --socket_path, --tls_cert_dir, --log_file
#
# Upstream's init script already does all of it; it only wants the two variables.
echo "==> pointing the operator at 0.0.0.0:16000 (https)"
touch /etc/default/cuttlefish-operator
sed -i '/^operator_listen_address=/d; /^operator_https_port=/d; /^# Managed by hush/d' \
  /etc/default/cuttlefish-operator
cat >> /etc/default/cuttlefish-operator <<'EOF'
# Managed by hush/scripts/install-cuttlefish.sh
operator_listen_address=0.0.0.0
operator_https_port=16000
EOF

# An earlier version of this script wrote a drop-in. Remove it, or it keeps
# winning over the config above.
if [[ -f /etc/systemd/system/cuttlefish-operator.service.d/10-hush-listen.conf ]]; then
  rm -f /etc/systemd/system/cuttlefish-operator.service.d/10-hush-listen.conf
  rmdir --ignore-fail-on-non-empty /etc/systemd/system/cuttlefish-operator.service.d
  echo "    removed the obsolete systemd drop-in"
fi
systemctl daemon-reload

# Restart rather than leave it to the reboot below: on a re-run — which is how
# this config gets corrected on a host that is already set up — the packages and
# modules are already in place and the operator is the only thing that changed.
systemctl restart cuttlefish-operator || true
echo "    operator: $(systemctl is-active cuttlefish-operator)"

if lsmod | grep -q '^vhost_vsock' && id -nG "$TARGET_USER" | grep -q cvdnetwork; then
  cat <<EOF

==> done. The modules are loaded and $TARGET_USER is already in cvdnetwork, so
    this was a re-run and no reboot is needed — the operator has been restarted.

  cd ~/wash/hush && make cf-start     # boot a device (or cf-stop first)
  make cf-ui                          # print the UI URL

EOF
else
  cat <<EOF

==> done, but NOT usable until you reboot.

The reboot loads vhost_vsock / vhost_net and applies the udev rules; the group
changes also only take effect on a fresh login.

  sudo reboot

Afterwards, no root is needed again:

  cd ~/wash/hush && make cf-fetch     # download AOSP images (~3G, one time)
  make cf-start                       # boot a device
  make cf-ui                          # print the UI URL

EOF
fi
