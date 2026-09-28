# Pipeline local de mídia

`MediaGateway` recebe o caminho de um download local previamente verificado e uma função que informa se a execução ainda possui o job. Retorna ZIP completo, tamanho, SHA-256 e quantidade de frames. O chamador deve persistir o início da tentativa antes da chamada e fechar `LocalMediaResult` após assumir a responsabilidade pelo armazenamento/recuperação. Fechar remove somente os temporários daquela chamada e libera a capacidade local. O arquivo de entrada pertence ao chamador e não é removido pelo pipeline.

## Inspeção e extração

FFprobe decodifica o primeiro stream de vídeo e lê seus timestamps e durações. O limite é 300 segundos, inclusive. Também confere o formato real e a duração declarada quando presente. Para ASF/FLV sem duração por frame, considera o último intervalo observado entre timestamps; para um único frame sem duração, exige período informado pela taxa do stream. Timeline não verificável é rejeitada. Conteúdo sem vídeo, corrompido, sem imagens ou com formato fora de MOV/MP4, AVI, Matroska/WebM, ASF/WMV e FLV falha como `INVALID_MEDIA`.

FFmpeg extrai o primeiro stream a `fps=1`, sem áudio, legendas ou redimensionamento. PNGs passam por `image2pipe` para um parser com buffer fixo de 8 KiB, que verifica assinatura, chunks e CRC e os escreve diretamente no ZIP como `frame-000001.png`, etc. Não há uma imagem inteira em memória nem necessidade de materializar todos os PNGs no disco. O contador agregado inclui cada byte de PNG; o contador do ZIP inclui cabeçalhos e diretório central. Ambos rejeitam a escrita que excederia a quota, inclusive no arquivo corrente. Excesso resulta em `OUTPUT_LIMIT_EXCEEDED`, sem resultado parcial retornado.

O ZIP é fechado antes do retorno. Falha ou cancelamento interrompe processos e remove o ZIP parcial. Uma execução ocupa a capacidade até fechar o resultado, evitando acumular ZIPs locais enquanto o armazenamento está indisponível. Cada execução cria um diretório exclusivo; não há varredura/remoção de diretórios de outras execuções.

## Subprocessos e recursos

Comandos usam listas de argumentos, sem shell ou nomes do usuário como opções. O protocolo de entrada permitido é somente `file`, com demuxers limitados aos formatos aceitos; playlists e URLs externas não são aceitas. A saída PNG usa pipe local. Stderr é descartado para não expor metadados/conteúdo nem acumular logs ilimitados. São configurados dois threads de codec/filtro; os limites globais de CPU/RAM pertencem ao container.

FFprobe e FFmpeg compartilham um prazo monotônico de 600 segundos, configurável. O monitor verifica prazo e posse a cada 50 ms durante subprocessos. Ao expirar ou perder posse, força encerramento do processo e descendentes. `PROCESSING_TIMEOUT` é resultado de execução sujeito à política de tentativas; perda de posse, interrupção, falta de disco, falha de IO/inicialização e saída 137 são falhas operacionais, sem resultado de mídia terminal emitido pelo gateway. Nenhum stderr bruto é retornado. O pipeline não implementa retentativa, heartbeat, transação terminal ou ACK: isso cabe ao consumidor/orquestrador.

Antes da execução, verifica pelo menos 3 GiB livres no filesystem de temporários. Isso é admissão local, não reserva física contra escritores externos. Configure volumes e limites de container coerentes e exclusivos por instância. A configuração padrão usa `/app/.local/processing`, `/usr/bin/ffmpeg` e `/usr/bin/ffprobe`.

## Build e validação

O runtime fixa `ffmpeg=8.1.2-r0` do Alpine 3.24, na imagem JRE Temurin `21.0.12_8-jre-alpine-3.24`. O alvo `media-test` usa a mesma versão de pacote em JDK para executar JUnit. A atualização desse pacote requer repetir a matriz de formatos; um codec específico ainda pode não ser suportado mesmo dentro de um container aceito.

```bash
make verify
make integration-media
make image
```

`make verify` executa unitários, incluindo processos Java locais para cancelamento/timeout, e mantém o gate de 90% em linhas/branches. A integração de mídia cria vídeos sintéticos em MP4, AVI, MOV, MKV, WMV, FLV e WebM, valida PNGs e ZIP, duração exata/excessiva, quota exata/excessiva, conteúdo inválido, ausência de vídeo, playlist remota, timeout e perda de posse. O container de teste tem 1 GiB de memória e duas CPUs; os relatórios são copiados para `target/media-reports`, ignorado pelo Git. O volume Docker `fiapx-processing-media-maven` contém somente cache de dependências.

## Integrações pendentes

O [armazenamento](storage.md) oferece download com conferência de bytes/hash, intenção de escrita durável, retenção do ZIP para recuperação após restart e limpeza de órfãos com consulta à persistência. O chamador deve reter o ZIP antes de fechar o resultado de mídia. A ligação ao consumidor SQS, renovação de posse, publicação de eventos, agendamento da limpeza e ACK manual permanecem pendentes. Nenhum endpoint público ou consumo de fila é ativado por este pipeline.

Referências técnicas: [protocolos FFmpeg](https://ffmpeg.org/ffmpeg-protocols.html), [formatos e image2pipe](https://ffmpeg.org/ffmpeg-formats.html), [campos e saída do FFprobe](https://ffmpeg.org/ffprobe.html).
