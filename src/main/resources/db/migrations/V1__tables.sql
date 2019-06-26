create sequence spade_id_seq;

create table feedback (
  behandling_id varchar(100) not null,
  when_given timestamp not null,
  element varchar(100) not null,
  reason varchar(100) not null,
  constraint pk_spade_feedback primary key (behandling_id)
);

