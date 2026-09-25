package br.com.paywallet.pix;

final class Documents {

    private Documents() {
    }

    /**
     * CPFs are shown as ***.456.789-**, the masking used by Pix apps; CNPJs identify companies and are public.
     */
    static String mask(String document) {
        if (document == null) {
            return null;
        }
        if (document.length() == 11) {
            return "***." + document.substring(3, 6) + "." + document.substring(6, 9) + "-**";
        }
        if (document.length() == 14) {
            return document.substring(0, 2) + "." + document.substring(2, 5) + "." + document.substring(5, 8)
                    + "/" + document.substring(8, 12) + "-" + document.substring(12);
        }
        return "***";
    }
}
