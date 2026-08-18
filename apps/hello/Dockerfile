# Two stages: the SDK compiles, the chiseled ASP.NET runtime serves. "Chiseled"
# is Ubuntu with everything the app does not need removed — no shell, no
# package manager, no root user — which is both the smallest and the least
# useful thing to break into of the images Microsoft publishes.
FROM mcr.microsoft.com/dotnet/sdk:10.0-noble AS build
WORKDIR /src

# The project file alone first, so the restore layer is only invalidated when a
# dependency changes rather than on every source edit.
COPY src/Hello.csproj ./
RUN dotnet restore

COPY src/ ./
RUN dotnet publish -c Release --no-restore -o /app

# FROM mcr.microsoft.com/dotnet/aspnet:10.0-noble-chiseled AS final
FROM mcr.microsoft.com/dotnet/aspnet:10.0-noble-amd64 AS final
WORKDIR /app
COPY --from=build /app ./

# Run as the image's own unprivileged user rather than root. $APP_UID is how
# the .NET images expect you to name it; it resolves to 1654, the "app" user
# (app:x:1654:1654, shell /bin/false), which is the same UID the chart pins in
# the pod's securityContext. Change one and the other has to follow.
#
# The build stage above stays root so it can write /app; only what runs in
# production drops the privilege, and it owns none of the files it serves.
USER $APP_UID

# 8080 is the .NET 8+ default for a non-root container: binding 80 needs a
# capability this user does not have. Named "http" in the Service.
ENV ASPNETCORE_HTTP_PORTS=8080
EXPOSE 8080

ENTRYPOINT ["dotnet", "hello.dll"]
