Organiza el código de este proyecto siguiendo EXACTAMENTE esta jerarquía de carpetas.
Respeta los nombres, los sufijos y la dirección de las dependencias.

## Jerarquía

```
<raiz>/
├── presentation/                  # Capa de entrada
│   ├── <Feature><Canal>Controller     # Contrato del endpoint
│   ├── impl/                          # Implementación de los controllers + utilidades propias
│   │   └── idempotency/
│   ├── dto/                           # <Nombre>RequestDto / <Nombre>ResponseDto
│   ├── mapper/                        # dto <-> domain/internal_request
│   └── context/                       # Resolución del contexto del request
│
├── business/
│   └── usecase/                   # Interfaces: un caso de uso = una acción de negocio (<Verbo><Objeto>UseCase)
│       ├── impl/                  # <Nombre>UseCaseImpl, 1 a 1 con su interfaz
│       │   └── <familia>/
│       │       ├── channel/       # Variantes por canal
│       │       └── provider/      # Variantes por proveedor
│       └── <familia>/{channel,provider}/   # Interfaces de esa familia
│
├── domain/                        # Modelo puro, sin dependencias de otras capas
│   ├── data/                      # Objetos de negocio y snapshots (<Nombre>Data, <Nombre>Snapshot)
│   ├── internal_request/          # Request/Response entre capas internas y hacia clients
│   ├── external_request/<sistema>/# Contratos de sistemas externos
│   ├── exception/                 # Excepciones de negocio (<Nombre>Exception)
│   └── <familia>/                 # Commands propios de la familia
│
├── persistence/                   # Acceso a datos
│   ├── <Nombre>Persistence            # Interfaz que consume business
│   ├── impl/                          # <Nombre>PersistenceImpl
│   ├── entity/                        # <Nombre>Entity
│   └── dto/                           # Proyecciones / resultados de consultas
│
├── client/                        # Salida hacia sistemas externos
│   ├── rest/
│   │   ├── api/                   # <Sistema><Recurso>Api
│   │   └── impl/                  # <Sistema><Recurso>ClientImpl
│   ├── sdk/                       # <Proveedor>Client
│   │   └── impl/
│   ├── mapper/                    # <Sistema><Recurso>ClientMapper (domain <-> client dto)
│   └── dto/                       # DTOs del cliente (subcarpeta por proveedor)
│
├── cache/                         # <Nombre>CacheService + impl/
├── config/                        # <Nombre>Config, con subcarpetas por tema
├── enums/                         # Enums compartidos, sufijo Enum
└── util/                          # Utilidades transversales

recursos/
├── configuración por ambiente (local, dev, ci, prod)
├── db/changelog/                  # Registro maestro de migraciones
├── db/migrations/<yyyyMMddHHmmss>_<TICKET>   # Una migración por ticket
├── scripts/
└── templates/

tests/                             # Espejo exacto de la jerarquía principal
                                   # (business/, cache/, client/, config/, enums/, persistence/, presentation/, util/)
```

## Reglas de dependencia

`presentation → business.usecase → (persistence | client | cache) → domain`

1. `presentation` solo llama a interfaces de `business.usecase`; nunca a `persistence` ni `client` directamente.
2. `business` trabaja con objetos de `domain` y depende de interfaces, nunca de entidades ni de DTOs de presentación.
3. `persistence` expone `domain/data` hacia arriba; las `entity` no salen de esta capa.
4. `client` traduce `domain/internal_request` ↔ `client/dto` con un `*ClientMapper`.
5. `domain` no depende de ninguna otra capa.
6. Toda interfaz va en el paquete padre y su implementación en `impl/` con sufijo `Impl`.
7. Cada cambio de esquema = una migración nueva en `db/migrations`, registrada en el changelog maestro.
8. Cada clase nueva lleva su test en la misma ruta dentro de `tests/`.

## Tarea

<describe aquí lo que hay que construir o mover>

Antes de escribir código, lista los archivos que vas a crear o modificar y en qué carpeta va cada uno.
