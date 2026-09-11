# UPX Compress Plugin (Jenkins)

Build step do Jenkins que compacta o executável gerado no build (Delphi 7,
Delphi/RAD Studio, .NET, etc.) usando o [UPX](https://upx.github.io/), sem
depender de um caminho fixo no disco do agente: na primeira execução em cada
workspace, o plugin baixa o binário oficial do UPX diretamente das
[releases do GitHub (upx/upx)](https://github.com/upx/upx/releases),
confere o hash SHA-256 contra um valor fixado no código-fonte e só então o
executa. Nada de UPX é versionado dentro deste repositório.

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

## Atualizando a versão do UPX

A versão, as URLs de download e os hashes SHA-256 esperados ficam em
constantes no topo de `UpxCompressBuilder.java`
(`UPX_VERSION`, `WIN64_SHA256`, `LINUX_AMD64_SHA256`). Para adotar uma nova
versão do UPX:

1. Baixe os artefatos `upx-X.Y.Z-win64.zip` e `upx-X.Y.Z-amd64_linux.tar.xz`
   em https://github.com/upx/upx/releases/tag/vX.Y.Z.
2. Calcule o SHA-256 de cada um (`sha256sum arquivo` no Linux ou
   `certutil -hashfile arquivo SHA256` no Windows) e confira contra o hash
   publicado na página da release.
3. Atualize as constantes no código com a nova versão e os novos hashes.

## Licença

Código deste plugin: MIT (veja `LICENSE`).
O UPX em si (baixado em tempo de execução, não redistribuído por este
repositório) é licenciado pelo próprio projeto sob GPL-2.0-or-later com
exceção para executáveis comprimidos — veja https://github.com/upx/upx/blob/master/LICENSE.
