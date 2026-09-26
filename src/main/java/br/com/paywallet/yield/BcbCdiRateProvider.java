package br.com.paywallet.yield;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import br.com.paywallet.exception.ExternalServiceException;

/** Central bank SGS series 12: the daily CDI, published for business days only. Public, no credentials. */
@Component
@ConditionalOnProperty(name = "app.yield.cdi-source", havingValue = "bcb", matchIfMissing = true)
class BcbCdiRateProvider implements CdiRateProvider {

    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RestClient restClient;
    private final String url;

    BcbCdiRateProvider(RestClient externalRestClient, YieldProperties props) {
        this.restClient = externalRestClient;
        this.url = props.bcbSeriesUrl();
    }

    private record Point(String data, String valor) {
    }

    @Override
    public SortedMap<LocalDate, BigDecimal> dailyRates(LocalDate from, LocalDate to) {
        List<Point> points;
        try {
            points = restClient.get()
                    .uri(url + "?formato=json&dataInicial={from}&dataFinal={to}", BR_DATE.format(from), BR_DATE.format(to))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Point>>() { });
        } catch (HttpClientErrorException.NotFound e) {
            return new TreeMap<>(); // the series answers 404 when the range has no business day
        } catch (RestClientException e) {
            throw new ExternalServiceException("CDI rate source unavailable", e);
        }
        var rates = new TreeMap<LocalDate, BigDecimal>();
        if (points != null) {
            points.forEach(p -> rates.put(LocalDate.parse(p.data(), BR_DATE), new BigDecimal(p.valor())));
        }
        return rates;
    }
}
