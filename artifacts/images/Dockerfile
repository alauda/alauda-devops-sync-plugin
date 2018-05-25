FROM alpine:3.7

COPY copy.sh /
RUN chmod +x copy.sh
CMD ["/copy.sh"]

COPY ../../target/alauda-devops-sync.hpi /plugin/

