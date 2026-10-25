# syntax=docker/dockerfile:1
# SPDX-License-Identifier: GPL-3.0-or-later
FROM python:3.11-slim-bookworm@sha256:f06d01d4ba3f8ad5af45a3ec87df19c1d317d8e8961edc0fabda61811af6f7cf

RUN dpkg --add-architecture i386 && apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates fontconfig libasound2 libfreetype6 libgl1 libgl1-mesa-dri \
    libpulse0 libvulkan1 mesa-vulkan-drivers libx11-6 libxcb1 libxcursor1 libxext6 libxfixes3 \
    libxi6 libxinerama1 libxkbcommon0 libxrandr2 libxrender1 \
    libc6:i386 libstdc++6:i386 libasound2:i386 libfreetype6:i386 \
    libgl1:i386 libgl1-mesa-dri:i386 libpulse0:i386 libvulkan1:i386 \
    mesa-vulkan-drivers:i386 libx11-6:i386 libxcb1:i386 libxcursor1:i386 \
    libxext6:i386 libxfixes3:i386 libxi6:i386 libxinerama1:i386 \
    libxkbcommon0:i386 libxrandr2:i386 libxrender1:i386 \
    && rm -rf /var/lib/apt/lists/* \
    && sed -i -E 's@/usr/lib/(i386|x86_64)-linux-gnu/libvulkan_lvp.so@libvulkan_lvp.so@' \
       /usr/share/vulkan/icd.d/lvp_icd.x86_64.json /usr/share/vulkan/icd.d/lvp_icd.i686.json \
    && ln -sfn /usr/local/bin/python3 /usr/bin/python3
