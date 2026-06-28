self:
{ config, lib, pkgs, ... }:

let
  cfg = config.services.kwickchat;
in
{
  options.services.kwickchat = {
    enable = lib.mkEnableOption "the kwickchat chat server";

    package = lib.mkOption {
      type = lib.types.package;
      default = self.packages.${pkgs.stdenv.hostPlatform.system}.default;
      defaultText = lib.literalExpression "kwickchat.packages.\${system}.default";
      description = "The kwickchat package to run.";
    };

    host = lib.mkOption {
      type = lib.types.str;
      default = "127.0.0.1";
      description = ''
        Address to bind. Defaults to localhost; put a TLS-terminating reverse
        proxy (nginx, Caddy, …) in front for public access.
      '';
    };

    port = lib.mkOption {
      type = lib.types.port;
      default = 5660;
      description = "TCP port to listen on.";
    };

    dataDir = lib.mkOption {
      type = lib.types.path;
      default = "/var/lib/kwickchat";
      description = ''
        Directory holding the SQLite database. With the default value systemd's
        StateDirectory manages it automatically; if you change it, make sure the
        service can write there.
      '';
    };

    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Whether to open the listen port in the firewall.";
    };
  };

  config = lib.mkIf cfg.enable {
    systemd.services.kwickchat = {
      description = "kwickchat chat server";
      wantedBy = [ "multi-user.target" ];
      after = [ "network.target" ];

      environment = {
        KWICKCHAT_HOST = cfg.host;
        KWICKCHAT_PORT = toString cfg.port;
        KWICKCHAT_DB = "${cfg.dataDir}/kwickchat.db";
      };

      serviceConfig = {
        ExecStart = lib.getExe cfg.package;
        Restart = "on-failure";
        RestartSec = 2;

        DynamicUser = true;
        StateDirectory = "kwickchat";
        WorkingDirectory = cfg.dataDir;

        # Hardening
        NoNewPrivileges = true;
        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
        PrivateDevices = true;
        ReadWritePaths = [ cfg.dataDir ];
        RestrictAddressFamilies = [ "AF_INET" "AF_INET6" ];
        RestrictNamespaces = true;
        LockPersonality = true;
        SystemCallArchitectures = "native";
      };
    };

    networking.firewall.allowedTCPPorts = lib.optionals cfg.openFirewall [ cfg.port ];
  };
}
