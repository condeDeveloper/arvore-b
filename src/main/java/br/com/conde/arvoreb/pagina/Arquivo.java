package br.com.conde.arvoreb.pagina;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * O arquivo de páginas.
 *
 * <p>Um arquivo de banco de dados é um vetor de blocos iguais, e o número da
 * página é o índice: a página 7 começa no byte {@code 7 * 4096}. Não há
 * busca, não há índice de páginas — a aritmética resolve.
 *
 * <p>A página 0 é reservada para o cabeçalho, que guarda onde está a raiz.
 * Sem ele, abrir o arquivo de novo não saberia por onde começar.
 *
 * <p>O cache é um {@link LinkedHashMap} em modo de acesso, que é um LRU com
 * quatro linhas. Ele importa mais do que parece: numa árvore de três níveis,
 * a raiz é lida em <b>toda</b> operação, e sem cache seriam milhões de
 * leituras do mesmo bloco.
 */
public final class Arquivo implements Closeable {

  /** A página 0 guarda o cabeçalho. */
  public static final int PAGINA_DO_CABECALHO = 0;

  /** Assinatura do arquivo, para não abrir qualquer coisa como se fosse. */
  public static final int ASSINATURA = 0x42545245; // "BTRE"

  private final RandomAccessFile arquivo;
  private final Map<Integer, Pagina> cache;
  private int totalDePaginas;
  private int raiz;
  private long leiturasDeDisco;
  private long escritasDeDisco;

  private Arquivo(RandomAccessFile arquivo, int limiteDoCache) {
    this.arquivo = arquivo;
    this.cache =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<Integer, Pagina> maisAntiga) {
            return size() > limiteDoCache;
          }
        };
  }

  /** Abre, criando o cabeçalho quando o arquivo é novo. */
  public static Arquivo abrir(Path caminho, int limiteDoCache) throws IOException {
    boolean novo = !Files.exists(caminho) || Files.size(caminho) == 0;
    Arquivo arquivo = new Arquivo(new RandomAccessFile(caminho.toFile(), "rw"), limiteDoCache);

    // Se o cabeçalho não presta, o arquivo precisa ser fechado antes de a
    // exceção subir. Sem isso o descritor vaza — e no Windows ele ainda
    // segura o arquivo, impedindo até que alguém o apague.
    try {
      if (novo) {
        arquivo.totalDePaginas = 1;
        arquivo.raiz = Pagina.NENHUMA;
        arquivo.gravarCabecalho();
      } else {
        arquivo.lerCabecalho();
      }
    } catch (IOException | RuntimeException erro) {
      arquivo.arquivo.close();

      throw erro;
    }

    return arquivo;
  }

  public int raiz() {
    return raiz;
  }

  public void raiz(int numero) throws IOException {
    this.raiz = numero;

    gravarCabecalho();
  }

  public int totalDePaginas() {
    return totalDePaginas;
  }

  public long leiturasDeDisco() {
    return leiturasDeDisco;
  }

  public long escritasDeDisco() {
    return escritasDeDisco;
  }

  /** Cria uma página nova no fim do arquivo. */
  public Pagina criar(byte tipo) {
    Pagina pagina = new Pagina(totalDePaginas, tipo);

    totalDePaginas += 1;
    cache.put(pagina.numero(), pagina);

    return pagina;
  }

  /** Lê uma página, do cache quando possível. */
  public Pagina ler(int numero) throws IOException {
    if (numero < 1 || numero >= totalDePaginas) {
      throw new IllegalArgumentException("Página " + numero + " fora do arquivo (0.." + (totalDePaginas - 1) + ")");
    }

    Pagina guardada = cache.get(numero);

    if (guardada != null) {
      return guardada;
    }

    byte[] bloco = new byte[Pagina.TAMANHO];

    arquivo.seek((long) numero * Pagina.TAMANHO);
    arquivo.readFully(bloco);
    leiturasDeDisco += 1;

    Pagina pagina = Pagina.de(numero, bloco);

    cache.put(numero, pagina);

    return pagina;
  }

  /** Grava uma página. */
  public void gravar(Pagina pagina) throws IOException {
    arquivo.seek((long) pagina.numero() * Pagina.TAMANHO);
    arquivo.write(pagina.bytes());
    escritasDeDisco += 1;

    cache.put(pagina.numero(), pagina);
  }

  /** Força os bytes para o disco. */
  public void sincronizar() throws IOException {
    // `write` entrega ao sistema operacional; só o `getFD().sync()` promete
    // que os bytes sobrevivem a uma queda de energia.
    arquivo.getFD().sync();
  }

  private void gravarCabecalho() throws IOException {
    byte[] bloco = new byte[Pagina.TAMANHO];
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bloco);

    buffer.putInt(ASSINATURA);
    buffer.putInt(Pagina.TAMANHO);
    buffer.putInt(raiz);
    buffer.putInt(totalDePaginas);

    arquivo.seek(0);
    arquivo.write(bloco);
    escritasDeDisco += 1;
  }

  private void lerCabecalho() throws IOException {
    byte[] bloco = new byte[Pagina.TAMANHO];

    arquivo.seek(0);
    arquivo.readFully(bloco);
    leiturasDeDisco += 1;

    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bloco);
    int assinatura = buffer.getInt();

    if (assinatura != ASSINATURA) {
      throw new IOException("Este arquivo não é uma árvore B (assinatura 0x" + Integer.toHexString(assinatura) + ")");
    }

    int tamanhoDaPagina = buffer.getInt();

    // Um arquivo gravado com página de outro tamanho não pode ser lido com
    // este: a aritmética de deslocamento daria em qualquer lugar.
    if (tamanhoDaPagina != Pagina.TAMANHO) {
      throw new IOException("O arquivo usa página de " + tamanhoDaPagina + " bytes e este programa usa " + Pagina.TAMANHO);
    }

    raiz = buffer.getInt();
    totalDePaginas = buffer.getInt();
  }

  /** Esvazia o cache, forçando as próximas leituras a irem ao disco. */
  public void esquecer() {
    cache.clear();
  }

  @Override
  public void close() throws IOException {
    gravarCabecalho();
    arquivo.close();
  }
}
