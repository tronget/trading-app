FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/quotes .

FROM alpine:3.21
WORKDIR /app
COPY --from=build /out/quotes /app/quotes
EXPOSE 8081
ENTRYPOINT ["/app/quotes"]
