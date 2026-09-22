package br.com.conde.arvoreb.pagina;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Uma página: o bloco de tamanho fixo que vai e volta do disco.
 *
 * <p>Esta é a decisão que define uma árvore B e a separa de uma árvore binária:
 * <b>o nó tem o tamanho de um bloco de disco</b>. Uma árvore binária com um
 * milhão de chaves tem uns 20 níveis, e cada nível é uma leitura de disco —
 * 20 idas. Uma árvore B com páginas de 4 KB guarda dezenas de chaves por nó,
 * tem 3 ou 4 níveis para o mesmo milhão, e faz 3 ou 4 idas.
 *
 * <p>Não é a quantidade de comparações que importa; é a quantidade de vezes
 * que se atravessa a fronteira entre memória e disco. Por isso o tamanho da
 * página aqui é 4096: é o tamanho de um setor lógico na maioria dos sistemas
 * de arquivos, e ler menos que isso custa o mesmo que ler isso.
 *
 * <p>O formato de uma página:
 *
 * <pre>
 *   byte   tipo          0 = folha, 1 = interna
 *   short  quantidade    quantas chaves
 *   int    proxima       só na folha: a folha seguinte, para varrer em ordem
 *   ...    entradas
 * </pre>
 */
public final class Pagina {

  /** O tamanho fixo de toda página. */
  public static final int TAMANHO = 4096;

  public static final byte FOLHA = 0;
  public static final byte INTERNA = 1;

  /** Cabeçalho: tipo, quantidade e o ponteiro para a folha seguinte. */
  public static final int CABECALHO = 1 + 2 + 4;

  /** Nenhuma página. Zero seria ambíguo com a página 0. */
  public static final int NENHUMA = -1;

  private final int numero;
  private byte tipo;
  private int proxima = NENHUMA;
  private final List<String> chaves = new ArrayList<>();
  private final List<String> valores = new ArrayList<>();
  private final List<Integer> filhos = new ArrayList<>();

  public Pagina(int numero, byte tipo) {
    this.numero = numero;
    this.tipo = tipo;
  }

  public int numero() {
    return numero;
  }

  public boolean ehFolha() {
    return tipo == FOLHA;
  }

  public int proxima() {
    return proxima;
  }

  public void proxima(int valor) {
    this.proxima = valor;
  }

  public List<String> chaves() {
    return chaves;
  }

  public List<String> valores() {
    return valores;
  }

  public List<Integer> filhos() {
    return filhos;
  }

  public int quantidade() {
    return chaves.size();
  }

  /**
   * Quantos bytes esta página ocupa se for gravada agora.
   *
   * <p>É o que decide quando dividir. Uma árvore B de livro-texto divide por
   * <b>contagem</b> de chaves, o que só funciona quando toda chave tem o mesmo
   * tamanho. Com chave e valor de tamanho variável, a conta certa é por
   * <b>bytes</b>: uma página com três chaves gigantes está cheia, e uma com
   * cinquenta chaves de dois caracteres não está.
   */
  public int bytesOcupados() {
    int total = CABECALHO;

    for (int i = 0; i < chaves.size(); i += 1) {
      total += 2 + utf8(chaves.get(i)).length;

      if (ehFolha()) {
        total += 2 + utf8(valores.get(i)).length;
      }
    }

    if (!ehFolha()) {
      total += 4 * filhos.size();
    }

    return total;
  }

  /** A página cabe no bloco? */
  public boolean cabe() {
    return bytesOcupados() <= TAMANHO;
  }

  /** Acha a posição da chave, ou onde ela entraria. */
  public int procurar(String chave) {
    int baixo = 0;
    int alto = chaves.size();

    // Busca binária: é o que faz uma página de 4 KB com 200 chaves custar 8
    // comparações em memória, e não 200.
    while (baixo < alto) {
      int meio = (baixo + alto) >>> 1;
      int comparacao = chaves.get(meio).compareTo(chave);

      if (comparacao < 0) {
        baixo = meio + 1;
      } else {
        alto = meio;
      }
    }

    return baixo;
  }

  /** Indica se a chave está exatamente nesta página. */
  public boolean contem(String chave) {
    int i = procurar(chave);

    return i < chaves.size() && chaves.get(i).equals(chave);
  }

  /** Grava a página num bloco de {@link #TAMANHO} bytes. */
  public byte[] bytes() {
    if (!cabe()) {
      throw new IllegalStateException(
          "A página " + numero + " ocupa " + bytesOcupados() + " bytes e o bloco tem " + TAMANHO);
    }

    ByteBuffer buffer = ByteBuffer.allocate(TAMANHO);

    buffer.put(tipo);
    buffer.putShort((short) chaves.size());
    buffer.putInt(proxima);

    for (int i = 0; i < chaves.size(); i += 1) {
      escreverTexto(buffer, chaves.get(i));

      if (ehFolha()) {
        escreverTexto(buffer, valores.get(i));
      }
    }

    if (!ehFolha()) {
      for (int filho : filhos) {
        buffer.putInt(filho);
      }
    }

    return buffer.array();
  }

  /** Lê uma página de um bloco. */
  public static Pagina de(int numero, byte[] bloco) {
    ByteBuffer buffer = ByteBuffer.wrap(bloco);

    byte tipo = buffer.get();

    if (tipo != FOLHA && tipo != INTERNA) {
      throw new IllegalArgumentException("Página " + numero + " com tipo desconhecido: " + tipo);
    }

    Pagina pagina = new Pagina(numero, tipo);
    int quantidade = buffer.getShort();

    pagina.proxima = buffer.getInt();

    for (int i = 0; i < quantidade; i += 1) {
      pagina.chaves.add(lerTexto(buffer));

      if (tipo == FOLHA) {
        pagina.valores.add(lerTexto(buffer));
      }
    }

    if (tipo == INTERNA) {
      // Um nó interno com n chaves tem n+1 filhos: as chaves são as
      // fronteiras entre eles.
      for (int i = 0; i <= quantidade; i += 1) {
        pagina.filhos.add(buffer.getInt());
      }
    }

    return pagina;
  }

  private static byte[] utf8(String texto) {
    return texto.getBytes(StandardCharsets.UTF_8);
  }

  private static void escreverTexto(ByteBuffer buffer, String texto) {
    byte[] bytes = utf8(texto);

    if (bytes.length > Short.MAX_VALUE) {
      throw new IllegalArgumentException("Texto de " + bytes.length + " bytes não cabe numa entrada");
    }

    buffer.putShort((short) bytes.length);
    buffer.put(bytes);
  }

  private static String lerTexto(ByteBuffer buffer) {
    int tamanho = buffer.getShort();
    byte[] bytes = new byte[tamanho];

    buffer.get(bytes);

    return new String(bytes, StandardCharsets.UTF_8);
  }

  @Override
  public String toString() {
    return (ehFolha() ? "folha" : "interna") + " #" + numero + " " + chaves;
  }
}
