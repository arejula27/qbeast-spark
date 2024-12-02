
{ pkgs ? import <nixpkgs> {} }:

let
  # Versión, URL y hash del binario de Spark
  sparkVersion = "3.5.0";
  sparkUrl = "https://archive.apache.org/dist/spark/spark-${sparkVersion}/spark-${sparkVersion}-bin-hadoop3.tgz";
  sparkHash = "8883c67e0a138069e597f3e7d4edbbd5c3a565d50b28644aad02856a1ec1da7cb92b8f80454ca427118f69459ea326eaa073cf7b1a860c3b796f4b07c2101319";

  # Derivación para preparar el binario de Spark
  spark = pkgs.stdenv.mkDerivation {
    pname = "spark";
    version = sparkVersion;

    # Descarga el tarball
    src = pkgs.fetchurl {
      url = sparkUrl;
      sha512 = sparkHash;
    };
    installPhase = ''
      mkdir -p $out
      tar -xzf $src --strip-components=1 -C $out
    '';
    meta = {
      description = "Apache Spark ${sparkVersion} with prebuilt Hadoop3 binaries";
      licenses= pkgs.licenses.apache2;
      homepage = "https://spark.apache.org";
    };
  };
in

# Shell de desarrollo que incluye Spark
pkgs.mkShell {
  packages = [
    pkgs.zulu8
    pkgs.sbt
    spark
   ]; 
    # Añade el binario de Spark al entorno
  SPARK_HOME = "${spark.out}";
  shellHook = ''
    echo "Your development environment for qbeast is ready, happy coding!"
    echo "Try 'spark-shell' or 'sbt test' to start."
  '';
}
