# UPX Compress Plugin (Jenkins)

Build step do Jenkins que compacta o executável gerado no build (Delphi 7,
Delphi/RAD Studio, .NET, etc.) usando o [UPX](https://upx.github.io/), sem
depender de um caminho fixo no disco do agente: o binário do UPX viaja
embutido dentro do próprio `.hpi` e é extraído para o workspace do build em
tempo de execução.

## Uso

Em um job Freestyle: **Adicionar etapa de build → Compactar executável com UPX**.

| Campo         | Descrição                                              | Padrão            |
|---------------|---------------------------------------------------------|--------------------|
| Executável    | Caminho relativo ao workspace, ex: `Projeto.exe`         | (obrigatório)      |
| Opções        | Argumentos de linha de comando do UPX                    | `--best --lzma`    |

Em Pipeline (declarativo/scripted):

```groovy
step([$class: 'UpxCompressBuilder', executable: "${PROJETO}.exe", options: '--best --lzma'])

// ou, com o Symbol registrado:
upxCompress executable: "${PROJETO}.exe", options: '--best --lzma'
```

## Build local

Pré-requisitos: JDK 11+, Maven 3.8+.

```bash
mvn hpi:run      # sobe um Jenkins local em http://localhost:8080/jenkins para testar
mvn package      # gera target/upx-compress.hpi
```

## Instalação

1. Baixe o `.hpi` gerado (ou de uma release deste repositório).
2. No Jenkins: **Gerenciar Jenkins → Plugins → Advanced settings → Deploy Plugin**
   e faça upload do arquivo `.hpi`.
3. Reinicie o Jenkins se solicitado.

## Antes de compilar: adicione os binários do UPX

Este repositório **não inclui os binários** do UPX (são arquivos de terceiros).
Veja `src/main/resources/upx-bin/LEIA-ME.txt` para instruções de download e
licenciamento.

## Licença

Código deste plugin: MIT (veja `LICENSE`).
O binário do UPX embutido é distribuído sob a licença própria do projeto UPX
(GPL-2.0-or-later com exceção para executáveis comprimidos) — veja
`LICENSE-upx.txt` após adicionar os binários.
