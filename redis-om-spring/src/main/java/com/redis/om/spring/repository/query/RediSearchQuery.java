package com.redis.om.spring.repository.query;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.data.geo.Point;
import org.springframework.data.keyvalue.core.KeyValueOperations;
import org.springframework.data.mapping.PropertyPath;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.query.Parameter;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.QueryMethodEvaluationContextProvider;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.repository.query.parser.AbstractQueryCreator;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;
import org.springframework.data.util.Pair;
import org.springframework.data.util.Streamable;

import com.google.gson.Gson;
import com.redis.om.spring.annotations.Aggregation;
import com.redis.om.spring.annotations.GeoIndexed;
import com.redis.om.spring.annotations.Indexed;
import com.redis.om.spring.annotations.NumericIndexed;
import com.redis.om.spring.annotations.Searchable;
import com.redis.om.spring.annotations.TagIndexed;
import com.redis.om.spring.annotations.TextIndexed;
import com.redis.om.spring.ops.RedisModulesOperations;
import com.redis.om.spring.ops.search.SearchOperations;
import com.redis.om.spring.repository.query.clause.QueryClause;
import com.redis.om.spring.serialization.gson.GsonBuidlerFactory;

import io.redisearch.AggregationResult;
import io.redisearch.Query;
import io.redisearch.Schema.FieldType;
import io.redisearch.SearchResult;
import io.redisearch.aggregation.AggregationBuilder;
import lombok.ToString;

@ToString
public class RediSearchQuery implements RepositoryQuery {

  private static final Log logger = LogFactory.getLog(RediSearchQuery.class);

  private final QueryMethod queryMethod;
  private final String searchIndex;

  private RediSearchQueryType type;
  private static final Gson gson = GsonBuidlerFactory.getBuilder().create();
  private String value;

  private RepositoryMetadata metadata;

  // query fields
  private String[] returnFields;

  // aggregation field
  private String[] load;

  // is native? e.g. @Query or @Annotation
  private boolean annotationBased;

  //
  private List<Pair<String,QueryClause>> queryFields = new ArrayList<Pair<String,QueryClause>>();

  // for non @Param annotated dynamic names
  private List<String> paramNames = new ArrayList<String>();
  private Class<?> domainType;

  RedisModulesOperations<String, String> modulesOperations;

  private boolean isANDQuery = false;

  @SuppressWarnings("unchecked")
  public RediSearchQuery(QueryMethod queryMethod, RepositoryMetadata metadata,
      QueryMethodEvaluationContextProvider evaluationContextProvider, KeyValueOperations keyValueOperations,
      RedisModulesOperations<?, ?> rmo, Class<? extends AbstractQueryCreator<?, ?>> queryCreator) {
    logger.debug(String.format("Creating %s query method", queryMethod.getName()));

    this.modulesOperations = (RedisModulesOperations<String, String>) rmo;
    this.queryMethod = queryMethod;
    this.searchIndex = this.queryMethod.getEntityInformation().getJavaType().getSimpleName() + "Idx";
    this.metadata = metadata;
    this.domainType = this.queryMethod.getEntityInformation().getJavaType();

    Class<?> repoClass = metadata.getRepositoryInterface();
    @SuppressWarnings("rawtypes")
    Class[] params = queryMethod.getParameters().stream().map(p -> p.getType()).toArray(Class[]::new);

    try {
      java.lang.reflect.Method method = repoClass.getDeclaredMethod(queryMethod.getName(), params);
      if (method.isAnnotationPresent(com.redis.om.spring.annotations.Query.class)) {
        com.redis.om.spring.annotations.Query queryAnnotation = method
            .getAnnotation(com.redis.om.spring.annotations.Query.class);
        this.type = RediSearchQueryType.QUERY;
        this.annotationBased = true;
        this.value = queryAnnotation.value();
        this.returnFields = queryAnnotation.returnFields();
      } else if (method.isAnnotationPresent(Aggregation.class)) {
        Aggregation aggregation = method.getAnnotation(Aggregation.class);
        this.type = RediSearchQueryType.AGGREGATION;
        this.annotationBased = true;
        this.value = aggregation.value();
        this.load = aggregation.load();
      } else if (queryMethod.getName().equalsIgnoreCase("search")) {
        this.type = RediSearchQueryType.QUERY;
        queryFields.add(Pair.of("__all__", QueryClause.FullText_ALL));
        this.returnFields = new String[] {};
      } else {

        isANDQuery = QueryClause.hasContainingAllClause(queryMethod.getName());

        String methodName = isANDQuery ? QueryClause.getPostProcessMethodName(queryMethod.getName()) : queryMethod.getName();

        PartTree pt = new PartTree(methodName, metadata.getDomainType());

        Streamable<Part> queryParts = pt.getParts();
        for (Part part : queryParts) {
          PropertyPath propertyPath = part.getProperty();

          List<PropertyPath> path = StreamSupport
              .stream(propertyPath.spliterator(), false)
              .collect(Collectors.toList());
          extractQueryFields(domainType, part, path);
        }

        this.type = RediSearchQueryType.QUERY;
        this.returnFields = new String[] {};
      }
    } catch (NoSuchMethodException | SecurityException e) {
      logger.debug(String.format("Could not resolved query method %s(%s): %s", queryMethod.getName(), Arrays.toString(params), e.getMessage()));
    }
  }

  private void extractQueryFields(Class<?> type, Part part, List<PropertyPath> path) {
    extractQueryFields(type, part, path, 0);
  }

  private void extractQueryFields(Class<?> type, Part part, List<PropertyPath> path, int level) {
    String property = path.get(level).getSegment();
    String key = part.getProperty().toDotPath().replace(".", "_");

    Field field;
    try {
      field = type.getDeclaredField(property);

      if (field.isAnnotationPresent(TextIndexed.class) || field.isAnnotationPresent(Searchable.class)) {
        queryFields.add(Pair.of(key, QueryClause.get(FieldType.FullText, part.getType())));
      } else if (field.isAnnotationPresent(TagIndexed.class)) {
        queryFields.add(Pair.of(key, QueryClause.get(FieldType.Tag, part.getType())));
      } else if (field.isAnnotationPresent(GeoIndexed.class)) {
        queryFields.add(Pair.of(key, QueryClause.get(FieldType.Geo, part.getType())));
      } else if (field.isAnnotationPresent(NumericIndexed.class)) {
        queryFields.add(Pair.of(key, QueryClause.get(FieldType.Numeric, part.getType())));
      } else if (field.isAnnotationPresent(Indexed.class)) {
        //
        // Any Character class -> Tag Search Field
        //
        if (CharSequence.class.isAssignableFrom(field.getType())) {
          queryFields.add(Pair.of(key, QueryClause.get(FieldType.Tag, part.getType())));
        }
        //
        // Any Numeric class -> Numeric Search Field
        //
        else if (Number.class.isAssignableFrom(field.getType())) {
          queryFields.add(Pair.of(key, QueryClause.get(FieldType.Numeric, part.getType())));
        }
        //
        // Set
        //
        else if (Set.class.isAssignableFrom(field.getType())) {
          if (isANDQuery) {
            queryFields.add(Pair.of(key, QueryClause.Tag_CONTAINING_ALL));
          } else {
            queryFields.add(Pair.of(key, QueryClause.get(FieldType.Tag, part.getType())));
          }
        }
        //
        // Point
        //
        else if (field.getType() == Point.class) {
          queryFields.add(Pair.of(key, QueryClause.get(FieldType.Geo, part.getType())));
        }
        //
        // Recursively explore the fields for @Indexed annotated fields
        //
        else {
          extractQueryFields(field.getType(), part, path, level + 1);
        }
      }
    } catch (NoSuchFieldException e) {
      logger.info(String.format("Did not find a field named %s", key));
    }
  }

  @Override
  public Object execute(Object[] parameters) {
    if (type == RediSearchQueryType.QUERY) {
      return executeQuery(parameters);
    } else /* if (type == RediSearchQueryType.AGGREGATION) */ {
      return executeAggregation(parameters);
    }
  }

  @Override
  public QueryMethod getQueryMethod() {
    return queryMethod;
  }

  public boolean isAnnotationBased() {
    return annotationBased;
  }

  private Object executeQuery(Object[] parameters) {
    SearchOperations<String> ops = modulesOperations.opsForSearch(searchIndex);
    Query query = new Query(prepareQuery(parameters));
    query.returnFields(returnFields);
    SearchResult searchResult = ops.search(query);
    // what to return
    Object result = null;
    if (queryMethod.getReturnedObjectType() == SearchResult.class) {
      result = searchResult;
    } else if (queryMethod.isQueryForEntity() && !queryMethod.isCollectionQuery()) {
      String jsonResult = searchResult.docs.isEmpty() ? "{}" : searchResult.docs.get(0).get("$").toString();
      result = gson.fromJson(jsonResult, queryMethod.getReturnedObjectType());
    } else if (queryMethod.isQueryForEntity() && queryMethod.isCollectionQuery()) {
      result = searchResult.docs.stream()
          .map(d -> gson.fromJson(d.get("$").toString(), queryMethod.getReturnedObjectType()))
          .collect(Collectors.toList());
    }

    return result;
  }

  private Object executeAggregation(Object[] parameters) {
    SearchOperations<String> ops = modulesOperations.opsForSearch(searchIndex);
    AggregationBuilder aggregation = new AggregationBuilder().load(load);
    AggregationResult aggregationResult = ops.aggregate(aggregation);

    // what to return
    Object result = null;
    if (queryMethod.getReturnedObjectType() == AggregationResult.class) {
      result = aggregationResult;
    } else if (queryMethod.isCollectionQuery()) {
      result = Collections.EMPTY_LIST;
    }

    return result;
  }

  private String prepareQuery(Object[] parameters) {
    logger.debug(
        String.format("parameters: %s", Arrays.toString(parameters)));

    StringBuilder preparedQuery = new StringBuilder();

    if (!queryFields.isEmpty()) {
      for (Pair<String,QueryClause> fieldClauses : queryFields) {
        String fieldName = fieldClauses.getFirst();
        QueryClause queryClause = fieldClauses.getSecond();
        int paramsCnt = queryClause.getValue().getNumberOfArguments();

        preparedQuery.append(queryClause.prepareQuery(fieldName, Arrays.copyOfRange(parameters, 0, paramsCnt)));
        preparedQuery.append(" ");

        parameters = Arrays.copyOfRange(parameters, paramsCnt, parameters.length);
      }
    } else {
      @SuppressWarnings("unchecked")
      Iterator<Parameter> iterator = (Iterator<Parameter>) queryMethod.getParameters().iterator();
      int index = 0;

      if (value != null && !value.isBlank()) {
        preparedQuery.append(value);
      }

      while (iterator.hasNext()) {
        Parameter p = iterator.next();
        String key = (p.getName().isPresent() ? p.getName().get() : (paramNames.size() > index ? paramNames.get(index) : ""));
        String v = "";

        if (parameters[index] instanceof Collection<?>) {
          @SuppressWarnings("rawtypes")
          Collection<?> c = (Collection) parameters[index];
          v = c.stream().map(n -> n.toString()).collect(Collectors.joining(" | "));
        } else {
          v = parameters[index].toString();
        }

        if (value != null && value.isBlank()) {
          preparedQuery.append("@"+key+":"+v).append(" ");
        } else {
          preparedQuery = new StringBuilder(preparedQuery.toString().replace("$"+key, v));
        }
        index++;
      }
    }

    logger.debug(String.format("query: %s", preparedQuery.toString()));

    return preparedQuery.toString();
  }

}
