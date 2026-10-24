package com.rogerio.telalimpa;

/**
 * Configurações pessoais. Edite aqui e compile de novo.
 */
final class Config {

    /** Nome usado na saudação: "Bom dia, Rogério". */
    static final String USER_NAME = "Rogério";

    /** Cidade do clima (Open-Meteo, sem chave). */
    static final String CITY = "Nova Friburgo";
    static final double LATITUDE = -22.2819;
    static final double LONGITUDE = -42.5311;

    /**
     * Chave gratuita do TMDB (themoviedb.org) para as indicações de filmes e séries.
     * Cole a chave "API Key (v3 auth)" entre as aspas. Vazio = indicações desligadas.
     */
    static final String TMDB_API_KEY = "c6eed519fada8ef8b09a55e7438cd8e1";

    /** A cada quantos segundos a imagem de fundo troca (filmes e séries em alta). */
    static final int BACKGROUND_SECONDS = 20;

    /** Apps marcados como favoritos na primeira abertura (nome exato, sem diferenciar maiúsculas). */
    static final String[] DEFAULT_FAVORITES = {
            "YouTube", "Netflix", "Globoplay", "Prime Video", "Disney+"
    };

    private Config() {
    }
}
