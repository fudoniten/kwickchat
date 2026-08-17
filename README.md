# kwickchat

A *very* simple chat site. **Every link is its own chat room** — visit any
subpath and you get a fresh chat. Pick a username, start talking, share the
link with your friends. It's a pastebin, but for chat.

Built for kids to coordinate Minecraft play times: no email, no accounts, no
social logins. Just a link and a name.

## How it works

- **Every URL is a room.** `https://chat.example.com/minecraft-crew` is one
  chat; `/secret-base` is another. The landing page (`/`) suggests a random,
  hard-to-guess room name you can share.
- **Username = a cookie.** The first time you visit a room you pick a name; a
  random token in a cookie reserves that name *for that room*. Two people can't
  take the same name in the same room. Clear your cookies and you simply pick a
  name again — nothing else is lost.
- **Messages persist** in a single SQLite file.
- **Live updates** arrive over Server-Sent Events, so new messages appear
  without refreshing.
- The UI is a scrollable history with a *jump-to-newest* button, an emoji
  palette, and a message box. Sending a message jumps you to the bottom.
- **Post-its.** A side panel holds one pinned note per person — "On the server
  Thu @ 8pm!" Each member can leave or overwrite their own note (max 128
  characters); it's tinted with their colour and stamped with their avatar so
  it's clear who posted it. Notes persist and update live for everyone. On
  phones the panel is a slide-over toggled with the 📌 button.
- **Unread badge.** While the tab is in the background, missed messages are
  counted on the favicon, the tab title, and the app icon where the browser
  supports one. It clears the moment you look at the tab again.
- **Away notifications.** Each member can opt into a phone notification for
  messages they miss while they're not at the keyboard — see below.
- **In-game relay.** A room can echo its chat into a Minecraft server, so
  whoever is already playing sees it without alt-tabbing. Each room picks its
  own server — see below.

Rooms are unguessable links shared only among friends — keep the link private.

### Getting pinged when you're away

The badge only helps if the browser is open. For "someone messaged you an hour
ago and you were outside", kwickchat can push to your phone through
[ntfy](https://ntfy.sh) — which suits this site because it needs no account and
no email, exactly like the rest of kwickchat. **Each person sets this up for
themselves**, in the room, with no help from whoever runs the server:

1. Hit the 🔕 button in the top bar.
2. Install the ntfy app (Android / iPhone / F-Droid links are in the dialog),
   or just leave `ntfy.sh/<your-topic>` open in a browser tab.
3. Subscribe to the topic kwickchat suggests — a random one like
   `kwick-3f9a1c02b7d5`, because **the topic name is the only secret**. Anyone
   who knows it can send you notifications, so treat it like the room link.
4. Hit Save. A test notification arrives immediately, which is the only real
   way to know the phone half of the setup worked.

After that you get a notification when **all** of these are true:

- somebody else posts a chat message in that room,
- you haven't been at the keyboard for two minutes, and
- you haven't already been notified in the last five minutes.

That last rule matters: a burst of thirty messages is one buzz, not thirty. The
page tells the server "I'm still here" while it's visible and stops the moment
it isn't, so a tab left open overnight does **not** count as being present.
Tapping the notification opens the room (once `KWICKCHAT_URL` is set). Hit 🔔
again to change the topic or turn it off.

Settings live per room, per browser — they hang off the same cookie as your
username, so clearing cookies means setting notifications up again.

### Showing the chat in Minecraft

A room can pipe what's said into a Minecraft server's chat, which is the point
where "are we on tonight?" reaches the people who are already on:

```
[minecraft-crew] <Steve> anyone on tonight?
```

Delivery is Minecraft's own [RCON](https://minecraft.wiki/w/RCON) console:
kwickchat connects, authenticates, and runs one `tellraw @a`. Nothing has to be
installed on the game server — just `enable-rcon=true`, `rcon.password` and
`rcon.port` in `server.properties`. Only chat messages are relayed (not joins
or post-its), and only outwards: what happens in the game does **not** come
back into kwickchat.

**Whoever runs kwickchat lists the servers; each room picks one of them.** The
list lives in a JSON file named by `KWICKCHAT_MINECRAFT`:

```json
{
  "servers": {
    "survival": { "host": "10.0.0.5", "port": 25575,
                  "password": "…", "label": "Survival world" },
    "creative": { "host": "10.0.0.6", "password": "…" }
  },
  "rooms":   { "minecraft-crew": "survival" },
  "default":  null,
  "locked":   false
}
```

| Key       | Meaning                                                                    |
|-----------|----------------------------------------------------------------------------|
| `servers` | The servers rooms may choose from. `port` defaults to `25575`, `label` to the name (it's what the UI shows). |
| `rooms`   | Where a room points before anyone chooses in the room itself.              |
| `default` | Where every other room points before anyone chooses. `null` means nowhere. |
| `locked`  | `true` freezes the above: rooms then can't change their own target.        |

Inside a room, the ⛏️ button in the top bar lists those servers by label and
lets any member point the room at one, or at none. Picking one says so in the
game straight away, which is the only real way to know the RCON half works.
The button is hidden entirely when no servers are configured.

The **host and password are deliberately not a per-room setting**: an RCON
password is a secret that shouldn't travel through a browser, and a host typed
into a box by a kid would make kwickchat a port scanner for the local network.
Rooms only ever name a server the operator already listed, and the browser is
told names and labels — never hosts or passwords. Keep the file out of world
readable paths; on NixOS point `minecraft-config-file` at a sops-nix/agenix
secret.

Message text is flattened before it's sent — one line, no control characters,
no `§` colour codes, 200 characters max — so nobody can recolour the whole
server's chat from a chat room. Relaying happens off to one side of sending: if
the game server is slow, down, or has the wrong password, the chat room carries
on and the failure lands in the log.

### Moderation log

These are kids, so a grown-up should be able to glance at what's being said.
Every chat message and post-it is echoed to the server's standard output, which
the systemd service captures in the journal:

```bash
journalctl -u kwickchat | grep -E '\[chat\]|\[note\]'
# [chat] room=minecraft-crew user=Steve :: anyone on tonight?
# [note] room=minecraft-crew user=Alex  :: free after dinner!
```

The full history also lives in the SQLite database if you want to query it
directly. Identity is cookie-based with self-chosen names, so the "who" is only
as trustworthy as a kid not clearing their cookies — fine for a known friend
group, but don't treat it as tamper-proof.

## Tech

| Part      | Choice                                                            |
|-----------|-------------------------------------------------------------------|
| Backend   | Clojure on the JDK's built-in `HttpServer` (no web-server dep)    |
| Storage   | SQLite via `org.xerial/sqlite-jdbc`                               |
| Frontend  | ClojureScript (no React/npm) — native `fetch` + `EventSource`     |
| Build     | `tools.build` → a single uberjar; ClojureScript via the Closure compiler |
| Packaging | Nix flake: a package plus a NixOS module                          |

All dependencies come from Maven Central, so there is no npm/node in the build.

## Develop & run locally

Requires a JDK and the [Clojure CLI](https://clojure.org/guides/install_clojure).

```bash
# Build the ClojureScript bundle + a runnable uberjar
clojure -T:build uber

# Run it (defaults: host 0.0.0.0, port 8080, state dir /var/lib/kwickchat)
# For local hacking, point the state dir somewhere you can write:
java -jar target/kwickchat.jar --dir ./state

# …then open http://localhost:8080/your-room-name
```

While iterating you can rebuild just the frontend with `clojure -T:build cljs`
and run the server from source with `clojure -M:run --dir ./state` (any writable
directory — the default `/var/lib/kwickchat` usually needs root).

### Configuration

The server reads these environment variables, each overridable by a matching
command-line flag (`--host`, `--port`, `--dir`, `--db`), which take precedence:

| Variable         | Flag     | Default             | Meaning                                              |
|------------------|----------|---------------------|------------------------------------------------------|
| `KWICKCHAT_HOST` | `--host` | `0.0.0.0`           | Address to bind                                      |
| `KWICKCHAT_PORT` | `--port` | `8080`              | Port to listen on                                    |
| `KWICKCHAT_DIR`  | `--dir`  | `/var/lib/kwickchat`| State directory for all persistent data              |
| `KWICKCHAT_DB`   | `--db`   | `$KWICKCHAT_DIR/kwickchat.db` | Explicit path to the SQLite file (overrides the state dir) |
| `KWICKCHAT_NTFY` | `--ntfy` | `https://ntfy.sh`   | ntfy server for away notifications; set it empty to switch them off |
| `KWICKCHAT_URL`  | `--url`  | *(none)*            | This site's public URL, so notifications link back to the room |
| `KWICKCHAT_MINECRAFT` | `--minecraft` | *(none)*   | JSON file listing the Minecraft servers rooms may relay chat into; unset switches the relay off |

All state that needs to survive a restart lives under the state directory — at
present that's the single SQLite database (`kwickchat.db`). The directory is
created on startup if it doesn't exist.

Note that the ntfy **server** is deliberately an operator setting, not a
per-user one: kwickchat POSTs to it, so letting a visitor name any host would
turn the server into an open request relay. Members choose only their topic.
Point `KWICKCHAT_NTFY` at your own ntfy instance if you'd rather not route
notifications through the public one — kwickchat contacts it only for members
who have opted in.

## Deploy on NixOS

The flake exposes a package and a NixOS module.

```nix
# flake.nix (your system config)
{
  inputs.kwickchat.url = "github:fudoniten/kwickchat";

  outputs = { self, nixpkgs, kwickchat, ... }: {
    nixosConfigurations.myhost = nixpkgs.lib.nixosSystem {
      system = "x86_64-linux";
      modules = [
        kwickchat.nixosModules.default
        {
          services.kwickchat = {
            enable = true;
            host = "127.0.0.1";   # sit behind a reverse proxy for TLS
            port = 8080;
            public-url = "https://chat.example.com"; # makes notifications tappable
            # state-directory = "/var/lib/kwickchat"; # persistent data location
            # ntfy-server = "https://ntfy.sh";  # "" turns away notifications off
            # minecraft-config-file = "/run/secrets/kwickchat-minecraft.json";
            # openFirewall = true; # only if you expose it directly
          };
        }
      ];
    };
  };
}
```

The module runs the server as a hardened systemd service under a dedicated
`kwickchat` system user and keeps the database in `state-directory` (default
`/var/lib/kwickchat`, created on activation). Point `state-directory` at any
persistent location you like. Front it with nginx/Caddy for HTTPS — SSE works
through a normal reverse proxy as long as response buffering is off (the server
already sends `X-Accel-Buffering: no` for nginx).

Set `public-url` to whatever address your users actually type, so away
notifications can link back to the room; `ntfy-server` picks where those
notifications go (`""` removes the feature from the UI).

`minecraft-config-file` points at the JSON above, listing the game servers
rooms may relay chat into. It contains RCON passwords, so keep it out of the
Nix store — a sops-nix/agenix secret readable by the `kwickchat` user is the
right shape. The service runs with `ProtectHome`, so the file must not live
under a home directory. Leave the option unset and the ⛏️ button never appears.

### One-time hash step

The Nix build fetches Maven dependencies in a fixed-output derivation, which
needs a content hash. The **first** `nix build` will fail with a hash mismatch
and print the correct value:

```
error: hash mismatch in fixed-output derivation '…kwickchat-maven-deps…':
         specified: sha256-AAAA…
            got:    sha256-Xx12…
```

Copy the `got:` value into `outputHash` in [`nix/package.nix`](nix/package.nix)
(replacing `lib.fakeHash`) and build again. Repeat only if you change
`deps.edn`.

## Project layout

```
deps.edn                     deps + build/run aliases
build.clj                    tools.build: cljs compile + uberjar
src/kwickchat/server.clj     HTTP server, routing, SSE, cookies, presence
src/kwickchat/db.clj         SQLite persistence
src/kwickchat/notify.clj     away notifications over ntfy
src/kwickchat/minecraft.clj  relaying room chat into Minecraft over RCON
src/kwickchat/client.cljs    the entire front end
resources/public/            index.html + style.css (main.js is built)
flake.nix, nix/              Nix package + NixOS module
```
