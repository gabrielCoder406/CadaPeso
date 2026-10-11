# Imagen mínima del servidor de CadaPeso (sin dependencias externas: solo Node).
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production RUTA_BD=/datos/cadapeso.db
COPY package.json ./
COPY src ./src
# La base SQLite vive en un volumen para no perderla al actualizar la imagen.
RUN mkdir -p /datos && chown node:node /datos
VOLUME ["/datos"]
USER node
EXPOSE 8080
CMD ["node", "--disable-warning=ExperimentalWarning", "src/index.js"]
