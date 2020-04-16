FROM golang:1.14-alpine AS build

WORKDIR /build
COPY . .
RUN cd server && CGO_ENABLED=0 go build
RUN mkdir /app && \
    mv server/server /app/server && \
    touch /app/config.toml && \
    mv server/webroot /app/webroot && \
    mv app/build/outputs/apk/release/app-release.apk /app/webroot/s/Wizzeye.apk

FROM scratch

COPY --from=build /app /
EXPOSE 8080
CMD ["/server"]
