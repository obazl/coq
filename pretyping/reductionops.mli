(************************************************************************)
(*         *   The Coq Proof Assistant / The Coq Development Team       *)
(*  v      *         Copyright INRIA, CNRS and contributors             *)
(* <O___,, * (see version control and CREDITS file for authors & dates) *)
(*   \VV/  **************************************************************)
(*    //   *    This file is distributed under the terms of the         *)
(*         *     GNU Lesser General Public License Version 2.1          *)
(*         *     (see LICENSE file for the text of the license)         *)
(************************************************************************)

open Names
open Constr
open EConstr
open Univ
open Evd
open Environ

(** Reduction Functions. *)

exception Elimconst

val debug_RAKAM : CDebug.t

module CredNative : Primred.RedNative with
  type elem = EConstr.t and type args = EConstr.t array and type evd = Evd.evar_map
  and type uinstance = EInstance.t

(** Machinery to customize the behavior of the reduction *)
module ReductionBehaviour : sig

  type t = NeverUnfold | UnfoldWhen of when_flags | UnfoldWhenNoMatch of when_flags
  and when_flags = { recargs : int list ; nargs : int option }

  val set : local:bool -> GlobRef.t -> t -> unit
  val get : GlobRef.t -> t option
  val print : GlobRef.t -> Pp.t
end

(** {6 Support for reduction effects } *)

type effect_name = string

(* [declare_reduction_effect name f] declares [f] under key [name];
   [name] must be a unique in "world". *)
val declare_reduction_effect : effect_name ->
  (Environ.env -> Evd.evar_map -> Constr.constr -> unit) -> unit

(* [set_reduction_effect cst name] declares effect [name] to be called when [cst] is found *)
val set_reduction_effect : Constant.t -> effect_name -> unit

(* [effect_hook env sigma key term] apply effect associated to [key] on [term] *)
val reduction_effect_hook : Environ.env -> Evd.evar_map -> Constant.t ->
  Constr.constr Lazy.t -> unit

module Stack : sig
  type 'a app_node

  val pr_app_node : ('a -> Pp.t) -> 'a app_node -> Pp.t

  type 'a case_stk =
    case_info * EInstance.t * 'a array * 'a pcase_return * 'a pcase_invert * 'a pcase_branch array

  type 'a member =
  | App of 'a app_node
  | Case of 'a case_stk
  | Proj of Projection.t
  | Fix of ('a, 'a) pfixpoint * 'a t
  | Primitive of CPrimitives.t * (Constant.t * EInstance.t) * 'a t * CPrimitives.args_red
  and 'a t = 'a member list

  val pr : ('a -> Pp.t) -> 'a t -> Pp.t

  val empty : 'a t
  val is_empty : 'a t -> bool

  val decomp_node_last : 'a app_node -> 'a t -> ('a * 'a t)
  [@@ocaml.deprecated "Use decomp_rev"]

  val compare_shape : 'a t -> 'a t -> bool

  exception IncompatibleFold2

  (** [fold2 f x sk1 sk2] folds [f] on any pair of term in [(sk1,sk2)].
      @return the result and the lifts to apply on the terms
      @raise IncompatibleFold2 when [sk1] and [sk2] have incompatible shapes *)
  val fold2 : ('a -> constr -> constr -> 'a) -> 'a ->
    constr t -> constr t -> 'a
  val map : ('a -> 'a) -> 'a t -> 'a t

  (** [append_app args sk] pushes array of arguments [args] on [sk] *)
  val append_app : 'a array -> 'a t -> 'a t

  (** [append_app_list args sk] pushes list of arguments [args] on [sk] *)
  val append_app_list : 'a list -> 'a t -> 'a t

  (** if [strip_app sk] = [(sk1,sk2)], then [sk = sk1 @ sk2] with
      [sk1] purely applicative and [sk2] does not start with an argument *)
  val strip_app : 'a t -> 'a t * 'a t

  (** @return (the nth first elements, the (n+1)th element, the remaining stack)
      if there enough of those *)
  val strip_n_app : int -> 'a t -> ('a t * 'a * 'a t) option

  (** [decomp sk] extracts the first argument of [sk] is there is some *)
  val decomp : 'a t -> ('a * 'a t) option

  (** [decomp sk] extracts the first argument of reversed stack [sk] is there is some *)
  val decomp_rev : 'a t -> ('a * 'a t) option

  (** [not_purely_applicative sk] *)
  val not_purely_applicative : 'a t -> bool

  (** [list_of_app_stack sk] either returns [Some sk] turned into a list of
      arguments if [sk] is purely applicative and [None] otherwise *)
  val list_of_app_stack : constr t -> constr list option

  (** [assign sk n a] changes the [n]th argument of [sk] with [a], counting from 0
      @raise an anomaly if there is less that [n] arguments available *)
  val assign : 'a t -> int -> 'a -> 'a t

  (** [args_size sk] returns the number of arguments available at the
      head of [sk] *)
  val args_size : 'a t -> int

  (** [tail n sk] drops the [n] first arguments of [sk]
      @raise [Invalid_argument] if there are not enough arguments *)
  val tail : int -> 'a t -> 'a t

  (** [nth sk n] returns the [n]-th argument of [sk], counting from 0
      @raise [Not_found] if there is no [n]th argument *)
  val nth : 'a t -> int -> 'a

  (** [zip sigma t sk] *)
  val zip : evar_map -> constr * constr t -> constr
end

(************************************************************************)

type reduction_function = env -> evar_map -> constr -> constr

type e_reduction_function = env -> evar_map -> constr -> evar_map * constr

type stack_reduction_function =
    env -> evar_map -> constr -> constr * constr list

(** {6 Generic Optimized Reduction Function using Closures } *)

val clos_norm_flags : CClosure.RedFlags.reds -> reduction_function
val clos_whd_flags : CClosure.RedFlags.reds -> reduction_function

(** Same as [(strong whd_beta[delta][iota])], but much faster on big terms *)
val nf_beta : reduction_function
val nf_betaiota : reduction_function
val nf_betaiotazeta : reduction_function
val nf_zeta : reduction_function
val nf_all : reduction_function
val nf_evar : evar_map -> constr -> constr

(** Lazy strategy, weak head reduction *)

val whd_evar :  evar_map -> constr -> constr
val whd_nored : reduction_function
val whd_beta : reduction_function
val whd_betaiota : reduction_function
val whd_betaiotazeta : reduction_function
val whd_all :  reduction_function
val whd_allnolet :  reduction_function
val whd_betalet : reduction_function

(** Removes cast and put into applicative form *)
val whd_nored_stack : stack_reduction_function
val whd_beta_stack : stack_reduction_function
val whd_betaiota_stack : stack_reduction_function
val whd_betaiotazeta_stack : stack_reduction_function
val whd_all_stack : stack_reduction_function
val whd_allnolet_stack : stack_reduction_function
val whd_betalet_stack : stack_reduction_function

(** {6 Head normal forms } *)

val whd_const : Constant.t -> reduction_function
val whd_delta_stack :  stack_reduction_function
val whd_delta :  reduction_function
val whd_betadeltazeta_stack :  stack_reduction_function
val whd_betadeltazeta :  reduction_function
val whd_zeta_stack : stack_reduction_function
val whd_zeta : reduction_function

val shrink_eta : Environ.env -> constr -> constr

(** Various reduction functions *)

val safe_evar_value : evar_map -> Constr.existential -> Constr.constr option

val beta_applist : evar_map -> constr * constr list -> constr

val hnf_prod_app     : env ->  evar_map -> constr -> constr -> constr
val hnf_prod_appvect : env ->  evar_map -> constr -> constr array -> constr
val hnf_prod_applist : env ->  evar_map -> constr -> constr list -> constr
val hnf_lam_app      : env ->  evar_map -> constr -> constr -> constr
val hnf_lam_appvect  : env ->  evar_map -> constr -> constr array -> constr
val hnf_lam_applist  : env ->  evar_map -> constr -> constr list -> constr

val splay_prod : env ->  evar_map -> constr -> (Name.t Context.binder_annot * constr) list * constr
val splay_lam : env ->  evar_map -> constr -> (Name.t Context.binder_annot * constr) list * constr
val splay_prod_assum : env ->  evar_map -> constr -> rel_context * constr

val splay_arity : env ->  evar_map -> constr -> (Name.t Context.binder_annot * constr) list * ESorts.t
(** Raises [Reduction.NotArity] *)

val sort_of_arity : env -> evar_map -> constr -> ESorts.t
(** Raises [Reduction.NotArity] *)

val splay_prod_n : env ->  evar_map -> int -> constr -> rel_context * constr
(** Raises [Invalid_argument] *)

val splay_lam_n : env ->  evar_map -> int -> constr -> rel_context * constr
(** Raises [Invalid_argument] *)

val reducible_mind_case : evar_map -> constr -> bool

val find_conclusion : env -> evar_map -> constr -> (constr, constr, ESorts.t, EInstance.t) kind_of_term
val is_arity : env ->  evar_map -> constr -> bool
val is_sort : env -> evar_map -> types -> bool

val contract_fix : evar_map -> fixpoint -> constr
val contract_cofix : evar_map -> cofixpoint -> constr

(** {6 Querying the kernel conversion oracle: opaque/transparent constants } *)
val is_transparent : Environ.env -> Constant.t tableKey -> bool

(** {6 Conversion Functions (uses closures, lazy strategy) } *)

type conversion_test = Constraint.t -> Constraint.t

val pb_is_equal : conv_pb -> bool
val pb_equal : conv_pb -> conv_pb

val is_conv : ?reds:TransparentState.t -> env -> evar_map -> constr -> constr -> bool
val is_conv_leq : ?reds:TransparentState.t -> env ->  evar_map -> constr -> constr -> bool
val is_fconv : ?reds:TransparentState.t -> conv_pb -> env ->  evar_map -> constr -> constr -> bool

(** [check_conv] Checks universe constraints only.
    pb defaults to CUMUL and ts to a full transparent state.
 *)
val check_conv : ?pb:conv_pb -> ?ts:TransparentState.t -> env ->  evar_map -> constr -> constr -> bool

(** [infer_conv] Adds necessary universe constraints to the evar map.
    pb defaults to CUMUL and ts to a full transparent state.
    @raise UniverseInconsistency iff catch_incon is set to false,
    otherwise returns false in that case.
 *)
val infer_conv : ?catch_incon:bool -> ?pb:conv_pb -> ?ts:TransparentState.t ->
  env -> evar_map -> constr -> constr -> evar_map option

(** Conversion with inference of universe constraints *)
val set_vm_infer_conv : (?pb:conv_pb -> env -> evar_map -> constr -> constr ->
  evar_map option) -> unit
val vm_infer_conv : ?pb:conv_pb -> env -> evar_map -> constr -> constr ->
  evar_map option


(** [infer_conv_gen] behaves like [infer_conv] but is parametrized by a
conversion function. Used to pretype vm and native casts. *)
val infer_conv_gen : (conv_pb -> l2r:bool -> evar_map -> TransparentState.t ->
    (Constr.constr, evar_map) Reduction.generic_conversion_function) ->
  ?catch_incon:bool -> ?pb:conv_pb -> ?ts:TransparentState.t -> env ->
  evar_map -> constr -> constr -> evar_map option

(** {6 Heuristic for Conversion with Evar } *)

type state = constr * constr Stack.t

type state_reduction_function =
    env -> evar_map -> state -> state

val pr_state : env -> evar_map -> state -> Pp.t

val whd_nored_state : state_reduction_function

val whd_betaiota_deltazeta_for_iota_state :
  TransparentState.t -> state_reduction_function

(** {6 Meta-related reduction functions } *)
type meta_instance_subst

val create_meta_instance_subst : Evd.evar_map -> meta_instance_subst

val meta_instance : env -> meta_instance_subst -> constr freelisted -> constr
val nf_meta       : env -> evar_map -> constr -> constr

exception AnomalyInConversion of exn
