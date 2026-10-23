# syntax=docker/dockerfile:1
# SPDX-License-Identifier: GPL-3.0-or-later
FROM python:3.11-slim-bookworm@sha256:f06d01d4ba3f8ad5af45a3ec87df19c1d317d8e8961edc0fabda61811af6f7cf

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates fontconfig libasound2 libfreetype6 libgl1 libgl1-mesa-dri \
    libpulse0 libvulkan1 libx11-6 libxcb1 libxcursor1 libxext6 libxfixes3 \
    libxi6 libxinerama1 libxkbcommon0 libxrandr2 libxrender1 \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s /usr/local/bin/python3 /usr/bin/python3
