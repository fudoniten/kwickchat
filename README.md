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

Rooms are unguessable links shared only among friends — keep the link private.

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

# Run it (defaults: host 0.0.0.0, port 8080, db ./kwickchat.db)
java -jar target/kwickchat.jar

# …then open http://localhost:8080/your-room-name
```

While iterating you can rebuild just the frontend with `clojure -T:build cljs`
and run the server from source with `clojure -M:run`.

### Configuration

The server reads three environment variables:

| Variable         | Default        | Meaning                          |
|------------------|----------------|----------------------------------|
| `KWICKCHAT_HOST` | `0.0.0.0`      | Address to bind                  |
| `KWICKCHAT_PORT` | `8080`         | Port to listen on                |
| `KWICKCHAT_DB`   | `kwickchat.db` | Path to the SQLite database file |

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
            # openFirewall = true; # only if you expose it directly
          };
        }
      ];
    };
  };
}
```

The module runs the server as a hardened `DynamicUser` systemd service and keeps
the database in `/var/lib/kwickchat`. Front it with nginx/Caddy for HTTPS — SSE
works through a normal reverse proxy as long as response buffering is off (the
server already sends `X-Accel-Buffering: no` for nginx).

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
src/kwickchat/server.clj     HTTP server, routing, SSE, cookies
src/kwickchat/db.clj         SQLite persistence
src/kwickchat/client.cljs    the entire front end
resources/public/            index.html + style.css (main.js is built)
flake.nix, nix/              Nix package + NixOS module
```
