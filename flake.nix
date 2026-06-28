{
  description = "kwickchat — a tiny pastebin-for-chat where every link is its own chat room";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f:
        nixpkgs.lib.genAttrs systems
          (system: f { inherit system; pkgs = nixpkgs.legacyPackages.${system}; });
    in
    {
      packages = forAllSystems ({ pkgs, ... }: rec {
        kwickchat = pkgs.callPackage ./nix/package.nix { };
        default = kwickchat;
      });

      # NixOS module:  imports = [ kwickchat.nixosModules.default ];
      nixosModules.default = import ./nix/module.nix self;

      devShells = forAllSystems ({ pkgs, ... }: {
        default = pkgs.mkShell {
          packages = [ pkgs.clojure pkgs.jdk pkgs.nodejs ];
        };
      });
    };
}
