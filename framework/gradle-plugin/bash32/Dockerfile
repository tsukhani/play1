# PF-227: the image test.sh beside this file runs BundleLauncherTest in, under bash 3.2.57, the
# bash a stock Mac starts a bundle with. Ubuntu, as on the fork's ubuntu-latest CI job, so that the
# bash is what differs from that job, and glibc, whose et_EE.UTF-8 collation lets the PF-184 locale
# case show its bug (musl, in Docker's own bash:3.2 image, does not collate). Ubuntu's bash 5 stays
# /bin/bash for the tests' stubs; the bash under test is /opt/bash-3.2/bin/bash.
FROM eclipse-temurin:25-jdk-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        autotools-dev bison ca-certificates curl gcc libc6-dev libncurses-dev locales make \
    && sed -i 's/^# *\(et_EE.UTF-8\)/\1/' /etc/locale.gen \
    && locale-gen \
    && rm -rf /var/lib/apt/lists/*

# GNU's tarball. Its signature by the bash maintainer (key 7C0135FB088AAF6C66C650B9BB5869F064EA74AB)
# was checked when this checksum was pinned. config.guess and config.sub come from autotools-dev:
# bash 3.2's own predate arm64. The two -Wno-error flags let a current gcc build its K&R C.
ARG BASH_SHA256=3fa9daf85ebf35068f090ce51283ddeeb3c75eb5bc70b1a4a7cb05868bfe06a4
RUN set -eux; \
    cd /usr/local/src; \
    curl -fsSLO https://ftp.gnu.org/gnu/bash/bash-3.2.57.tar.gz; \
    echo "${BASH_SHA256}  bash-3.2.57.tar.gz" | sha256sum -c -; \
    tar xzf bash-3.2.57.tar.gz; \
    cd bash-3.2.57; \
    cp /usr/share/misc/config.guess /usr/share/misc/config.sub support/; \
    CFLAGS='-O2 -Wno-error=implicit-int -Wno-error=implicit-function-declaration' \
        ./configure --prefix=/opt/bash-3.2 --without-bash-malloc; \
    make; \
    make install; \
    cd /; \
    rm -rf /usr/local/src/bash-3.2.57 /usr/local/src/bash-3.2.57.tar.gz; \
    test "$(/opt/bash-3.2/bin/bash -c 'echo $BASH_VERSION')" = '3.2.57(1)-release'

COPY run.sh /usr/local/bin/play1-launcher-bash32
ENTRYPOINT ["/usr/local/bin/play1-launcher-bash32"]
