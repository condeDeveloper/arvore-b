package br.com.conde.arvoreb.arvore;

import br.com.conde.arvoreb.pagina.Arquivo;
import br.com.conde.arvoreb.pagina.Pagina;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Uma árvore B+ em disco.
 *
 * <p>É B<b>+</b>, e não B, por uma escolha que todo banco de dados faz: os
 * valores ficam <b>só nas folhas</b>, e os nós internos guardam apenas
 * fronteiras. Duas consequências:
 *
 * <ul>
 *   <li>Os nós internos cabem muito mais chaves por página, porque não
 *       carregam valor nenhum — então a árvore fica mais rasa e cada busca
 *       toca menos páginas.
 *   <li>As folhas são <b>encadeadas</b>, e varrer um intervalo vira caminhar
 *       numa lista, sem voltar para a raiz a cada passo. É por isso que
 *       {@code WHERE id BETWEEN 10 AND 20} é barato num índice de banco.
 * </ul>
 *
 * <p>A divisão aqui é por <b>bytes ocupados</b>, não por contagem de chaves.
 * A árvore B de livro-texto usa contagem, o que só funciona com chave de
 * tamanho fixo; com texto variável, três chaves gigantes enchem a página e
 * cinquenta minúsculas não.
 */
public final class ArvoreB implements Closeable {

  /** Abaixo disso a página é considerada vazia demais e pede equilíbrio. */
  public static final int MINIMO = Pagina.TAMANHO / 4;

  private final Arquivo arquivo;
  private int tamanho;

  private ArvoreB(Arquivo arquivo, int tamanho) {
    this.arquivo = arquivo;
    this.tamanho = tamanho;
  }

  /** Abre ou cria a árvore. */
  public static ArvoreB abrir(Path caminho) throws IOException {
    return abrir(caminho, 64);
  }

  /** O mesmo, escolhendo quantas páginas ficam no cache. */
  public static ArvoreB abrir(Path caminho, int limiteDoCache) throws IOException {
    Arquivo arquivo = Arquivo.abrir(caminho, limiteDoCache);
    ArvoreB arvore = new ArvoreB(arquivo, 0);

    // O tamanho não é guardado no cabeçalho de propósito: mantê-lo em disco
    // exigiria uma escrita a mais por operação, e recontar na abertura custa
    // uma varredura das folhas — que já estão encadeadas.
    arvore.tamanho = arvore.contarFolhas();

    return arvore;
  }

  public int tamanho() {
    return tamanho;
  }

  public boolean vazia() {
    return tamanho == 0;
  }

  public Arquivo arquivo() {
    return arquivo;
  }

  /** Quantos níveis a árvore tem. Uma árvore vazia tem zero. */
  public int altura() throws IOException {
    if (arquivo.raiz() == Pagina.NENHUMA) {
      return 0;
    }

    int altura = 1;
    Pagina pagina = arquivo.ler(arquivo.raiz());

    while (!pagina.ehFolha()) {
      pagina = arquivo.ler(pagina.filhos().get(0));
      altura += 1;
    }

    return altura;
  }

  // ------------------------------------------------------------------ busca

  /** Busca uma chave. */
  public Optional<String> obter(String chave) throws IOException {
    exigirChave(chave);

    if (arquivo.raiz() == Pagina.NENHUMA) {
      return Optional.empty();
    }

    Pagina folha = descerAteFolha(chave);
    int i = folha.procurar(chave);

    if (i < folha.quantidade() && folha.chaves().get(i).equals(chave)) {
      return Optional.of(folha.valores().get(i));
    }

    return Optional.empty();
  }

  public boolean contem(String chave) throws IOException {
    return obter(chave).isPresent();
  }

  private Pagina descerAteFolha(String chave) throws IOException {
    Pagina pagina = arquivo.ler(arquivo.raiz());

    while (!pagina.ehFolha()) {
      pagina = arquivo.ler(pagina.filhos().get(escolherFilho(pagina, chave)));
    }

    return pagina;
  }

  /**
   * Qual filho segue esta chave.
   *
   * <p>A fronteira {@code chaves[i]} é a <b>menor chave</b> da subárvore
   * {@code filhos[i+1]}. Então uma chave igual à fronteira vai para a
   * direita, não para a esquerda — errar isso faz a busca não achar
   * exatamente as chaves que viraram separador, que é o bug mais silencioso
   * que esta estrutura tem.
   */
  private int escolherFilho(Pagina pagina, String chave) {
    int i = pagina.procurar(chave);

    return i < pagina.quantidade() && pagina.chaves().get(i).equals(chave) ? i + 1 : i;
  }

  // --------------------------------------------------------------- inserção

  /** O que sobe quando uma página é dividida. */
  private record Divisao(String fronteira, int novaPagina) {}

  /** Grava um valor. Chave que já existe é substituída. */
  public void colocar(String chave, String valor) throws IOException {
    exigirChave(chave);

    if (valor == null) {
      throw new IllegalArgumentException("O valor não pode ser nulo; use remover para apagar");
    }

    if (arquivo.raiz() == Pagina.NENHUMA) {
      Pagina folha = arquivo.criar(Pagina.FOLHA);

      folha.chaves().add(chave);
      folha.valores().add(valor);
      arquivo.gravar(folha);
      arquivo.raiz(folha.numero());
      tamanho = 1;

      return;
    }

    Divisao divisao = inserir(arquivo.ler(arquivo.raiz()), chave, valor);

    if (divisao == null) {
      return;
    }

    // A raiz dividiu: a árvore ganha um nível. É o único jeito de uma árvore B
    // crescer em altura, e é por isso que ela cresce pela raiz, não pelas
    // folhas — o que a mantém sempre equilibrada sem nenhuma rotação.
    Pagina nova = arquivo.criar(Pagina.INTERNA);

    nova.chaves().add(divisao.fronteira());
    nova.filhos().add(arquivo.raiz());
    nova.filhos().add(divisao.novaPagina());

    arquivo.gravar(nova);
    arquivo.raiz(nova.numero());
  }

  private Divisao inserir(Pagina pagina, String chave, String valor) throws IOException {
    if (pagina.ehFolha()) {
      int i = pagina.procurar(chave);

      if (i < pagina.quantidade() && pagina.chaves().get(i).equals(chave)) {
        pagina.valores().set(i, valor);
        arquivo.gravar(pagina);

        return pagina.cabe() ? null : dividirFolha(pagina);
      }

      pagina.chaves().add(i, chave);
      pagina.valores().add(i, valor);
      tamanho += 1;

      if (pagina.cabe()) {
        arquivo.gravar(pagina);

        return null;
      }

      return dividirFolha(pagina);
    }

    int indice = escolherFilho(pagina, chave);
    Divisao dofilho = inserir(arquivo.ler(pagina.filhos().get(indice)), chave, valor);

    if (dofilho == null) {
      return null;
    }

    pagina.chaves().add(indice, dofilho.fronteira());
    pagina.filhos().add(indice + 1, dofilho.novaPagina());

    if (pagina.cabe()) {
      arquivo.gravar(pagina);

      return null;
    }

    return dividirInterna(pagina);
  }

  private Divisao dividirFolha(Pagina folha) throws IOException {
    int meio = meioPorBytes(folha);
    Pagina nova = arquivo.criar(Pagina.FOLHA);

    while (folha.quantidade() > meio) {
      nova.chaves().add(folha.chaves().remove(meio));
      nova.valores().add(folha.valores().remove(meio));
    }

    // O encadeamento das folhas é refeito na divisão: sem isso, varrer um
    // intervalo pularia a metade nova.
    nova.proxima(folha.proxima());
    folha.proxima(nova.numero());

    arquivo.gravar(folha);
    arquivo.gravar(nova);

    // Numa folha a chave do meio é **copiada** para cima: ela continua aqui,
    // porque é onde o valor dela mora.
    return new Divisao(nova.chaves().get(0), nova.numero());
  }

  private Divisao dividirInterna(Pagina pagina) throws IOException {
    int meio = pagina.quantidade() / 2;
    String fronteira = pagina.chaves().remove(meio);
    Pagina nova = arquivo.criar(Pagina.INTERNA);

    while (pagina.quantidade() > meio) {
      nova.chaves().add(pagina.chaves().remove(meio));
    }

    while (pagina.filhos().size() > meio + 1) {
      nova.filhos().add(pagina.filhos().remove(meio + 1));
    }

    arquivo.gravar(pagina);
    arquivo.gravar(nova);

    // Num nó interno a chave do meio **sobe**: ela some daqui, porque um nó
    // interno só guarda fronteira, e ela virou a fronteira lá de cima.
    return new Divisao(fronteira, nova.numero());
  }

  /** Onde cortar para as duas metades ficarem parecidas em bytes. */
  private int meioPorBytes(Pagina pagina) {
    int total = pagina.bytesOcupados() - Pagina.CABECALHO;
    int acumulado = 0;

    for (int i = 0; i < pagina.quantidade(); i += 1) {
      acumulado += 2 + pagina.chaves().get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

      if (pagina.ehFolha()) {
        acumulado += 2 + pagina.valores().get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      }

      // Uma página com uma entrada só não tem onde cortar; nesse caso ela é
      // grande demais e a gravação vai reclamar, o que é melhor do que
      // dividir em nada.
      if (acumulado * 2 >= total && i > 0) {
        return i;
      }
    }

    return Math.max(1, pagina.quantidade() / 2);
  }

  // ---------------------------------------------------------------- remoção

  /** Apaga uma chave. Devolve se ela existia. */
  public boolean remover(String chave) throws IOException {
    exigirChave(chave);

    if (arquivo.raiz() == Pagina.NENHUMA) {
      return false;
    }

    boolean removeu = remover(arquivo.ler(arquivo.raiz()), chave);

    if (!removeu) {
      return false;
    }

    tamanho -= 1;

    Pagina raiz = arquivo.ler(arquivo.raiz());

    // A raiz interna que ficou sem chave nenhuma some, e o único filho dela
    // vira a raiz: é assim que a árvore encolhe em altura.
    if (!raiz.ehFolha() && raiz.quantidade() == 0) {
      arquivo.raiz(raiz.filhos().get(0));
    } else if (raiz.ehFolha() && raiz.quantidade() == 0) {
      arquivo.raiz(Pagina.NENHUMA);
    }

    return true;
  }

  private boolean remover(Pagina pagina, String chave) throws IOException {
    if (pagina.ehFolha()) {
      int i = pagina.procurar(chave);

      if (i >= pagina.quantidade() || !pagina.chaves().get(i).equals(chave)) {
        return false;
      }

      pagina.chaves().remove(i);
      pagina.valores().remove(i);
      arquivo.gravar(pagina);

      return true;
    }

    int indice = escolherFilho(pagina, chave);
    Pagina filho = arquivo.ler(pagina.filhos().get(indice));

    if (!remover(filho, chave)) {
      return false;
    }

    if (filho.bytesOcupados() < MINIMO || filho.quantidade() == 0) {
      equilibrar(pagina, indice);
    }

    return true;
  }

  /**
   * Devolve o equilíbrio depois de uma remoção.
   *
   * <p>Duas saídas, nesta ordem: se o irmão couber junto, <b>funde</b>; se não
   * couber, <b>empresta</b> uma entrada dele. A ordem importa: fundir reduz a
   * quantidade de páginas, que é o que evita um arquivo cheio de páginas pela
   * metade depois de muita remoção.
   */
  private void equilibrar(Pagina pai, int indice) throws IOException {
    int indiceDoIrmao = indice > 0 ? indice - 1 : indice + 1;

    if (indiceDoIrmao >= pai.filhos().size()) {
      return;
    }

    int esquerda = Math.min(indice, indiceDoIrmao);
    Pagina a = arquivo.ler(pai.filhos().get(esquerda));
    Pagina b = arquivo.ler(pai.filhos().get(esquerda + 1));
    String fronteira = pai.chaves().get(esquerda);

    int juntos = a.bytesOcupados() + b.bytesOcupados() - Pagina.CABECALHO + (a.ehFolha() ? 0 : 2 + fronteira.length());

    if (juntos <= Pagina.TAMANHO) {
      fundir(pai, esquerda, a, b, fronteira);

      return;
    }

    emprestar(pai, esquerda, a, b);
  }

  private void fundir(Pagina pai, int esquerda, Pagina a, Pagina b, String fronteira) throws IOException {
    if (a.ehFolha()) {
      a.chaves().addAll(b.chaves());
      a.valores().addAll(b.valores());
      a.proxima(b.proxima());
    } else {
      // Num nó interno a fronteira desce: ela era a separação entre os dois e
      // vira uma chave comum no nó fundido.
      a.chaves().add(fronteira);
      a.chaves().addAll(b.chaves());
      a.filhos().addAll(b.filhos());
    }

    pai.chaves().remove(esquerda);
    pai.filhos().remove(esquerda + 1);

    arquivo.gravar(a);
    arquivo.gravar(pai);
  }

  private void emprestar(Pagina pai, int esquerda, Pagina a, Pagina b) throws IOException {
    boolean daEsquerdaParaDireita = a.bytesOcupados() > b.bytesOcupados();

    if (a.ehFolha()) {
      if (daEsquerdaParaDireita) {
        int ultimo = a.quantidade() - 1;

        b.chaves().add(0, a.chaves().remove(ultimo));
        b.valores().add(0, a.valores().remove(ultimo));
      } else {
        a.chaves().add(b.chaves().remove(0));
        a.valores().add(b.valores().remove(0));
      }

      pai.chaves().set(esquerda, b.chaves().get(0));
    } else {
      if (daEsquerdaParaDireita) {
        int ultimo = a.quantidade() - 1;

        b.chaves().add(0, pai.chaves().get(esquerda));
        b.filhos().add(0, a.filhos().remove(a.filhos().size() - 1));
        pai.chaves().set(esquerda, a.chaves().remove(ultimo));
      } else {
        a.chaves().add(pai.chaves().get(esquerda));
        a.filhos().add(b.filhos().remove(0));
        pai.chaves().set(esquerda, b.chaves().remove(0));
      }
    }

    arquivo.gravar(a);
    arquivo.gravar(b);
    arquivo.gravar(pai);
  }

  // -------------------------------------------------------------- varredura

  /** Todas as entradas em ordem de chave. */
  public Map<String, String> tudo() throws IOException {
    return intervalo(null, null);
  }

  /**
   * As entradas entre duas chaves, com as duas pontas incluídas.
   *
   * <p>Desce até a folha do começo e <b>caminha pelo encadeamento</b>. É por
   * isso que um índice de banco responde uma faixa em tempo proporcional ao
   * tamanho da faixa, e não ao tamanho da tabela.
   */
  public Map<String, String> intervalo(String de, String ate) throws IOException {
    Map<String, String> resultado = new LinkedHashMap<>();

    if (arquivo.raiz() == Pagina.NENHUMA) {
      return resultado;
    }

    Pagina folha = de == null ? primeiraFolha() : descerAteFolha(de);

    while (folha != null) {
      for (int i = 0; i < folha.quantidade(); i += 1) {
        String chave = folha.chaves().get(i);

        if (de != null && chave.compareTo(de) < 0) {
          continue;
        }

        if (ate != null && chave.compareTo(ate) > 0) {
          return resultado;
        }

        resultado.put(chave, folha.valores().get(i));
      }

      folha = folha.proxima() == Pagina.NENHUMA ? null : arquivo.ler(folha.proxima());
    }

    return resultado;
  }

  /** As chaves em ordem. */
  public List<String> chaves() throws IOException {
    return new ArrayList<>(tudo().keySet());
  }

  private Pagina primeiraFolha() throws IOException {
    Pagina pagina = arquivo.ler(arquivo.raiz());

    while (!pagina.ehFolha()) {
      pagina = arquivo.ler(pagina.filhos().get(0));
    }

    return pagina;
  }

  /**
   * Quantas folhas estão em uso.
   *
   * <p>É o número que mostra se a fusão está funcionando: sem ela, apagar 98%
   * das chaves deixaria a mesma quantidade de folhas de antes, todas quase
   * vazias, e o arquivo continuaria do mesmo tamanho para sempre.
   */
  public int folhasEmUso() throws IOException {
    if (arquivo.raiz() == Pagina.NENHUMA) {
      return 0;
    }

    int total = 0;
    Pagina folha = primeiraFolha();

    while (folha != null) {
      total += 1;
      folha = folha.proxima() == Pagina.NENHUMA ? null : arquivo.ler(folha.proxima());
    }

    return total;
  }

  private int contarFolhas() throws IOException {
    if (arquivo.raiz() == Pagina.NENHUMA) {
      return 0;
    }

    int total = 0;
    Pagina folha = primeiraFolha();

    while (folha != null) {
      total += folha.quantidade();
      folha = folha.proxima() == Pagina.NENHUMA ? null : arquivo.ler(folha.proxima());
    }

    return total;
  }

  private static void exigirChave(String chave) {
    if (chave == null || chave.isEmpty()) {
      throw new IllegalArgumentException("A chave não pode ser nula nem vazia");
    }
  }

  @Override
  public void close() throws IOException {
    arquivo.close();
  }
}
